package jadx.core.dex.visitors.usage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import jadx.api.usage.IUsageInfoData;
import jadx.api.usage.IUsageInfoVisitor;
import jadx.core.clsp.ClspClass;
import jadx.core.clsp.ClspClassSource;
import jadx.core.dex.info.FieldInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.ICodeNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.utils.Utils;
import jadx.core.utils.exceptions.JadxRuntimeException;

import static jadx.core.utils.Utils.notEmpty;

public class UsageInfo implements IUsageInfoData {
	/**
	 * Oversize threshold for a single publish entry: larger value sets are prepared serially
	 * on the caller thread to bound parallel phase memory (baseline temporaries are reused).
	 */
	private static final int SERIAL_PREPARE_ENTRY_THRESHOLD = 1 << 18;
	/**
	 * Number of graph entries prepared by a single worker task,
	 * amortizes task overhead for the many small value sets.
	 */
	private static final int PREPARE_CHUNK_ENTRIES = 256;

	private final RootNode root;
	/**
	 * Worker-local collection mode: Apache legacy flag is stored locally and global side effects are
	 * deferred until merge.
	 */
	private final boolean localCollection;
	private final UseSet<ClassNode, ClassNode> clsDeps;
	private final UseSet<ClassNode, ClassNode> clsUsage;
	private final UseSet<ClassNode, MethodNode> clsUseInMth;
	private final UseSet<FieldNode, MethodNode> fieldUsage;
	// MethodNodeA -> Set of MethodNodes that MethodNodeA is called from
	private final UseSet<MethodNode, MethodNode> mthUsage;
	// MethodNodeA -> Set of MethodNodes that MethodNodeA calls
	private final UseSet<MethodNode, MethodNode> mthUses;
	// MethodNodeA -> Set of MethodInfos for methods that MethodNodeA calls that cannot be resolved
	private final UseSet<MethodNode, MethodInfo> unresolvedMthUsage;
	private final Map<MethodNode, Boolean> selfCalls = new HashMap<>();
	// local Apache legacy marker, merged into GradleInfoStorage on the caller thread
	private boolean localApacheHttpLegacy;

	public UsageInfo(RootNode root) {
		this(root, false, null);
	}

	UsageInfo(RootNode root, boolean localCollection, @Nullable UsageInfoParallelExecutor.ScanBudget scanBudget) {
		this.root = root;
		this.localCollection = localCollection;
		this.clsDeps = new UseSet<>(scanBudget);
		this.clsUsage = new UseSet<>(scanBudget);
		this.clsUseInMth = new UseSet<>(scanBudget);
		this.fieldUsage = new UseSet<>(scanBudget);
		this.mthUsage = new UseSet<>(scanBudget);
		this.mthUses = new UseSet<>(scanBudget);
		this.unresolvedMthUsage = new UseSet<>(scanBudget);
	}

	/**
	 * Merge a completed worker-local graph into this graph.
	 * Value sets are unioned per key (local sets are transferred if the key is absent),
	 * self call and Apache legacy flags are OR-ed. Must be invoked on a single (caller) thread.
	 */
	void mergeFrom(UsageInfo other) {
		clsDeps.mergeFrom(other.clsDeps);
		clsUsage.mergeFrom(other.clsUsage);
		clsUseInMth.mergeFrom(other.clsUseInMth);
		fieldUsage.mergeFrom(other.fieldUsage);
		mthUsage.mergeFrom(other.mthUsage);
		mthUses.mergeFrom(other.mthUses);
		unresolvedMthUsage.mergeFrom(other.unresolvedMthUsage);
		selfCalls.putAll(other.selfCalls);
		if (other.localApacheHttpLegacy) {
			markApacheHttpLegacy();
		}
	}

	@Override
	public void apply() {
		// re-read the kill switch on every apply, no static caching
		UsageInfoParallelExecutor executor = UsageInfoParallelExecutor.open(root.getArgs());
		try {
			apply(executor);
		} finally {
			executor.close();
		}
	}

	/**
	 * Apply with an epoch executor shared with the collection phase,
	 * falls back to the original serial implementation when the executor has no worker pool.
	 */
	void apply(UsageInfoParallelExecutor executor) {
		if (!executor.isParallel()) {
			applySerial();
			return;
		}
		// seven serial publish rounds (dependencies -> class useIn -> class useInMth
		// -> field useIn -> method useIn -> method used -> unresolved -> selfCalls),
		// only sorting/resolution of entry values is prepared in parallel
		publishUseSet(executor, clsDeps, Function.identity(), ClassNode::setDependencies);
		publishUseSet(executor, clsUsage, Function.identity(), ClassNode::setUseIn);
		publishUseSet(executor, clsUseInMth, this::resolveMthList, ClassNode::setUseInMth);
		publishUseSet(executor, fieldUsage, this::resolveMthList, FieldNode::setUseIn);
		publishUseSet(executor, mthUsage, this::resolveMthList, MethodNode::setUseIn);
		publishUseSet(executor, mthUses, this::resolveMthList, MethodNode::setUsed);
		publishUseSet(executor, unresolvedMthUsage, Function.identity(), MethodNode::setUnresolvedUsed);
		selfCalls.forEach(MethodNode::setCallsSelf);
	}

	/**
	 * Original serial apply implementation, kept bit-exact as a fallback and oracle.
	 */
	private void applySerial() {
		clsDeps.visit((cls, deps) -> cls.setDependencies(sortedList(deps)));
		clsUsage.visit((cls, deps) -> cls.setUseIn(sortedList(deps)));
		clsUseInMth.visit((cls, methods) -> cls.setUseInMth(resolveMthList(sortedList(methods))));
		fieldUsage.visit((field, methods) -> field.setUseIn(resolveMthList(sortedList(methods))));
		mthUsage.visit((mth, methods) -> mth.setUseIn(resolveMthList(sortedList(methods))));
		mthUses.visit((mth, methods) -> mth.setUsed(resolveMthList(sortedList(methods))));
		unresolvedMthUsage.visit((mth, unresolvedMethods) -> mth.setUnresolvedUsed(sortedList(unresolvedMethods)));
		selfCalls.forEach(MethodNode::setCallsSelf);
	}

	/**
	 * Publish entries in bounded chunks: one worker task prepares a whole chunk of entries,
	 * at most P chunks are in flight, results are published serially in original entry order.
	 */
	private <K, V extends Comparable<V>> void publishUseSet(UsageInfoParallelExecutor executor,
			UseSet<K, V> useSet,
			Function<List<V>, List<V>> transform,
			BiConsumer<K, List<V>> setter) {
		int chunksInFlight = executor.getParallelism();
		Deque<PendingChunk<K, V>> inFlight = new ArrayDeque<>();
		List<UsageInfoParallelExecutor.PreparedEntry<K, V>> chunk = new ArrayList<>(PREPARE_CHUNK_ENTRIES);
		Iterator<Entry<K, Set<V>>> entryIterator = useSet.entryIterator();
		while (entryIterator.hasNext()) {
			Entry<K, Set<V>> entry = entryIterator.next();
			UsageInfoParallelExecutor.PreparedEntry<K, V> preparedEntry =
					new UsageInfoParallelExecutor.PreparedEntry<>(entry.getKey(), entry.getValue());
			if (entry.getValue().size() > SERIAL_PREPARE_ENTRY_THRESHOLD) {
				// oversize entry: reuse baseline serial preparation with its baseline temporaries
				preparedEntry.setPrepareSerially(true);
			}
			chunk.add(preparedEntry);
			if (chunk.size() == PREPARE_CHUNK_ENTRIES) {
				submitChunk(executor, chunk, transform, inFlight);
				chunk = new ArrayList<>(PREPARE_CHUNK_ENTRIES);
				if (inFlight.size() == chunksInFlight) {
					publishDrainedChunks(executor, inFlight, transform, setter);
				}
			}
		}
		if (!chunk.isEmpty()) {
			submitChunk(executor, chunk, transform, inFlight);
		}
		publishDrainedChunks(executor, inFlight, transform, setter);
	}

	private <K, V extends Comparable<V>> void submitChunk(UsageInfoParallelExecutor executor,
			List<UsageInfoParallelExecutor.PreparedEntry<K, V>> chunk,
			Function<List<V>, List<V>> transform,
			Deque<PendingChunk<K, V>> inFlight) {
		Future<List<List<V>>> future;
		if (chunk.stream().allMatch(UsageInfoParallelExecutor.PreparedEntry::isPrepareSerially)) {
			// every entry is oversize, no parallel preparation needed
			future = null;
		} else {
			future = executor.submit(() -> {
				List<List<V>> results = new ArrayList<>(chunk.size());
				for (UsageInfoParallelExecutor.PreparedEntry<K, V> entry : chunk) {
					if (entry.isPrepareSerially()) {
						results.add(null);
					} else {
						@SuppressWarnings("unchecked")
						Set<V> values = (Set<V>) entry.getSourceValues();
						results.add(transform.apply(sortedList(values)));
					}
				}
				return results;
			});
		}
		inFlight.add(new PendingChunk<>(chunk, future));
	}

	private <K, V extends Comparable<V>> void publishDrainedChunks(UsageInfoParallelExecutor executor,
			Deque<PendingChunk<K, V>> inFlight,
			Function<List<V>, List<V>> transform,
			BiConsumer<K, List<V>> setter) {
		while (!inFlight.isEmpty()) {
			PendingChunk<K, V> pending = inFlight.pollFirst();
			Future<List<List<V>>> future = pending.future;
			if (future != null) {
				List<List<V>> results;
				try {
					results = future.get();
				} catch (ExecutionException e) {
					Throwable cause = e.getCause();
					if (cause instanceof Error) {
						// fatal errors fail the whole epoch
						throw (Error) cause;
					}
					// stop publishing at the failed entry, already published entries
					// keep the serial failure prefix semantics
					executor.cancelRunningTasks();
					if (cause instanceof RuntimeException) {
						throw (RuntimeException) cause;
					}
					throw new JadxRuntimeException("Usage info publish failed", cause);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					executor.cancelRunningTasks();
					throw new JadxRuntimeException("Usage info publish interrupted", e);
				}
				List<UsageInfoParallelExecutor.PreparedEntry<K, V>> chunk = pending.chunk;
				for (int i = 0; i < chunk.size(); i++) {
					UsageInfoParallelExecutor.PreparedEntry<K, V> entry = chunk.get(i);
					if (!entry.isPrepareSerially()) {
						entry.setValue(results.get(i));
					}
				}
			}
			// serial publish in original map entry order
			for (UsageInfoParallelExecutor.PreparedEntry<K, V> entry : pending.chunk) {
				if (entry.isPrepareSerially()) {
					@SuppressWarnings("unchecked")
					Set<V> values = (Set<V>) entry.getSourceValues();
					setter.accept(entry.getKey(), transform.apply(sortedList(values)));
				} else {
					setter.accept(entry.getKey(), entry.getValue());
				}
			}
		}
	}

	private static final class PendingChunk<K, V> {
		final List<UsageInfoParallelExecutor.PreparedEntry<K, V>> chunk;
		final @Nullable Future<List<List<V>>> future;

		PendingChunk(List<UsageInfoParallelExecutor.PreparedEntry<K, V>> chunk, @Nullable Future<List<List<V>>> future) {
			this.chunk = chunk;
			this.future = future;
		}
	}

	@Override
	public void applyForClass(ClassNode cls) {
		cls.setDependencies(sortedList(clsDeps.getOrDefault(cls, Collections.emptySet())));
		cls.setUseIn(sortedList(clsUsage.getOrDefault(cls, Collections.emptySet())));
		cls.setUseInMth(resolveMthList(sortedList(clsUseInMth.getOrDefault(cls, Collections.emptySet()))));
		for (FieldNode fld : cls.getFields()) {
			fld.setUseIn(resolveMthList(sortedList(fieldUsage.getOrDefault(fld, Collections.emptySet()))));
		}
		for (MethodNode mth : cls.getMethods()) {
			mth.setUseIn(resolveMthList(sortedList(mthUsage.getOrDefault(mth, Collections.emptySet()))));
			mth.setUsed(resolveMthList(sortedList(mthUses.getOrDefault(mth, Collections.emptySet()))));
			mth.setUnresolvedUsed(sortedList(unresolvedMthUsage.getOrDefault(mth, Collections.emptySet())));
			mth.setCallsSelf(selfCalls.getOrDefault(mth, false));
		}
	}

	@Override
	public void visitUsageData(IUsageInfoVisitor visitor) {
		clsDeps.visit((cls, deps) -> visitor.visitClassDeps(cls, sortedList(deps)));
		clsUsage.visit((cls, deps) -> visitor.visitClassUsage(cls, sortedList(deps)));
		clsUseInMth.visit((cls, methods) -> visitor.visitClassUseInMethods(cls, resolveMthList(sortedList(methods))));
		fieldUsage.visit((field, methods) -> visitor.visitFieldsUsage(field, resolveMthList(sortedList(methods))));
		mthUsage.visit((mth, methods) -> visitor.visitMethodsUsage(mth, resolveMthList(sortedList(methods))));
		mthUses.visit((mth, methods) -> visitor.visitMethodsUses(mth, resolveMthList(sortedList(methods))));
		unresolvedMthUsage.visit((mth, unresolvedMethods) -> visitor.visitUnresolvedMethodsUsage(mth, sortedList(unresolvedMethods)));
		for (Entry<MethodNode, Boolean> entry : selfCalls.entrySet()) {
			MethodNode mth = entry.getKey();
			Boolean selfCall = entry.getValue();
			visitor.visitIsSelfCall(mth, selfCall);
		}
		visitor.visitComplete();
	}

	public void clsUse(ClassNode cls, ArgType useType) {
		processType(useType, depCls -> clsUse(cls, depCls));
	}

	public void clsUse(MethodNode mth, ArgType useType) {
		processType(useType, depCls -> clsUse(mth, depCls));
	}

	public void clsUse(ICodeNode node, ArgType useType) {
		Consumer<ClassNode> consumer;
		switch (node.getAnnType()) {
			case CLASS:
				ClassNode cls = (ClassNode) node;
				consumer = depCls -> clsUse(cls, depCls);
				break;
			case METHOD:
				MethodNode mth = (MethodNode) node;
				consumer = depCls -> clsUse(mth, depCls);
				break;
			case FIELD:
				FieldNode fld = (FieldNode) node;
				ClassNode fldCls = fld.getParentClass();
				consumer = depCls -> clsUse(fldCls, depCls);
				break;
			default:
				throw new JadxRuntimeException("Unexpected use type: " + node.getAnnType());
		}
		processType(useType, consumer);
	}

	public void clsUse(MethodNode mth, ClassNode useCls) {
		ClassNode parentClass = mth.getParentClass();
		clsUse(parentClass, useCls);
		if (parentClass != useCls) {
			// exclude class usage in self methods
			clsUseInMth.add(useCls, mth);
		}
	}

	public void clsUse(ClassNode cls, ClassNode depCls) {
		ClassNode topParentClass = cls.getTopParentClass();
		clsDeps.add(topParentClass, depCls.getTopParentClass());

		clsUsage.add(depCls, cls);
		clsUsage.add(depCls, topParentClass);
	}

	/**
	 * Add method usage: {@code useMth} occurrence found in {@code mth} code
	 */
	public void methodUse(MethodNode mth, MethodNode useMth) {
		clsUse(mth, useMth.getParentClass());
		mthUsage.add(useMth, mth); // useMth is used in mth
		mthUses.add(mth, useMth); // mth uses useMth
		if (mth == useMth) {
			selfCalls.put(mth, true);
		}
		// implicit usage
		clsUse(mth, useMth.getReturnType());
		useMth.getMethodInfo().getArgumentsTypes().forEach(argType -> clsUse(mth, argType));
	}

	/**
	 * Add method usage: {@code useMth} occurrence found in {@code mth} code
	 */
	public void unresolvedMethodUse(MethodNode mth, MethodInfo useMth) {
		if (useMth.getRawFullId().equals("java.lang.Object.<init>()V")) {
			// ignore default object constructor (called in every constructor)
			return;
		}
		unresolvedMthUsage.add(mth, useMth);
	}

	public void fieldUse(MethodNode mth, FieldNode useFld) {
		clsUse(mth, useFld.getParentClass());
		fieldUsage.add(useFld, mth);
		// implicit usage
		clsUse(mth, useFld.getType());
	}

	public void fieldUse(ICodeNode node, FieldInfo useFld) {
		FieldNode fld = root.resolveField(useFld);
		if (fld == null) {
			return;
		}
		switch (node.getAnnType()) {
			case CLASS:
				// TODO: support "field in class" usage?
				// now use field parent class for "class in class" usage
				clsUse((ClassNode) node, fld.getParentClass());
				break;
			case METHOD:
				fieldUse((MethodNode) node, fld);
				break;
		}
	}

	/**
	 * Visit all class nodes found in subtypes of the provided type.
	 */
	private void processType(ArgType type, Consumer<ClassNode> consumer) {
		if (type == null || type == ArgType.OBJECT) {
			return;
		}
		if (type.isArray()) {
			processType(type.getArrayRootElement(), consumer);
			return;
		}
		if (type.isObject()) {
			// TODO: support custom handlers via API
			ClspClass clsDetails = root.getClsp().getClsDetails(type);
			if (clsDetails != null && clsDetails.getSource() == ClspClassSource.APACHE_HTTP_LEGACY_CLIENT) {
				markApacheHttpLegacy();
			}
			ClassNode clsNode = root.resolveClass(type);
			if (clsNode != null) {
				consumer.accept(clsNode);
			}
			List<ArgType> genericTypes = type.getGenericTypes();
			if (notEmpty(genericTypes)) {
				for (ArgType argType : genericTypes) {
					processType(argType, consumer);
				}
			}
			List<ArgType> extendTypes = type.getExtendTypes();
			if (notEmpty(extendTypes)) {
				for (ArgType extendType : extendTypes) {
					processType(extendType, consumer);
				}
			}
			ArgType wildcardType = type.getWildcardType();
			if (wildcardType != null) {
				processType(wildcardType, consumer);
			}
			// TODO: process 'outer' types (check TestOuterGeneric test)
		}
	}

	private void markApacheHttpLegacy() {
		if (localCollection) {
			// defer global side effect until a deterministic merge on the caller thread
			localApacheHttpLegacy = true;
		} else {
			root.getGradleInfoStorage().setUseApacheHttpLegacy(true);
		}
	}

	private static <T extends Comparable<T>> List<T> sortedList(Set<T> nodes) {
		if (nodes == null || nodes.isEmpty()) {
			return Collections.emptyList();
		}
		List<T> list = new ArrayList<>(nodes);
		Collections.sort(list);
		return list;
	}

	private List<MethodNode> resolveMthList(List<MethodNode> mthNodeList) {
		return Utils.collectionMap(mthNodeList,
				m -> root.resolveDirectMethod(m.getParentClass().getRawName(), m.getMethodInfo().getShortId()));
	}
}
