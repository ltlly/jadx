package jadx.core.dex.visitors.usage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.plugins.input.data.ICallSite;
import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.IFieldRef;
import jadx.api.plugins.input.data.IMethodHandle;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.plugins.input.data.annotations.EncodedValue;
import jadx.api.plugins.input.data.annotations.IAnnotation;
import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.api.plugins.input.data.attributes.types.AnnotationMethodParamsAttr;
import jadx.api.plugins.input.data.attributes.types.AnnotationsAttr;
import jadx.api.plugins.input.insns.InsnData;
import jadx.api.plugins.input.insns.Opcode;
import jadx.api.plugins.input.insns.custom.ICustomPayload;
import jadx.api.usage.IUsageInfoCache;
import jadx.api.usage.IUsageInfoData;
import jadx.core.dex.info.FieldInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.ICodeNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.visitors.AbstractVisitor;
import jadx.core.dex.visitors.JadxVisitor;
import jadx.core.dex.visitors.OverrideMethodVisitor;
import jadx.core.dex.visitors.SignatureProcessor;
import jadx.core.dex.visitors.rename.RenameVisitor;
import jadx.core.utils.exceptions.JadxRuntimeException;
import jadx.core.utils.input.InsnDataUtils;

@JadxVisitor(
		name = "UsageInfoVisitor",
		desc = "Scan class and methods to collect usage info and class dependencies",
		runAfter = {
				SignatureProcessor.class, // use types with generics
				OverrideMethodVisitor.class, // add method override as use
				RenameVisitor.class // sort by alias name
		}
)
public class UsageInfoVisitor extends AbstractVisitor {
	private static final Logger LOG = LoggerFactory.getLogger(UsageInfoVisitor.class);

	private static final String DEPENDENCY_SCAN_ERROR = "Dependency scan failed";
	// serial registration of recoverable scan errors into node attributes (original behavior)
	private static final BiConsumer<MethodNode, Exception> SERIAL_ERROR_SINK =
			(mth, e) -> mth.addError(DEPENDENCY_SCAN_ERROR, e);

	/**
	 * Parallel collection needs a non-trivial input and all code readers
	 * explicitly declaring class-exclusive scan capability, otherwise the original serial scan is used.
	 */
	private static final int MIN_CLASSES_FOR_PARALLEL = 32;
	private static final long MIN_INSN_UNITS_FOR_PARALLEL = 20_000;
	/**
	 * Tiny classes are grouped into shards to amortize per-task graph costs,
	 * a shard never contains more classes than this limit.
	 */
	private static final int MAX_SHARD_CLASSES = 16;
	/**
	 * Conservative memory estimate per raw instruction unit in a worker-local graph (8B reference
	 * width).
	 */
	private static final long ESTIMATED_BYTES_PER_INSN_UNIT = 48;

	@Override
	public void init(RootNode root) {
		IUsageInfoCache usageCache = root.getArgs().getUsageInfoCache();
		IUsageInfoData usageInfoData = usageCache.get(root);
		if (usageInfoData != null) {
			try {
				apply(usageInfoData);
				return;
			} catch (Exception e) {
				LOG.error("Failed to apply cached usage data", e);
			}
		}
		try (UsageInfoParallelExecutor executor = UsageInfoParallelExecutor.open(root.getArgs())) {
			IUsageInfoData collectedInfoData = buildUsageData(root, executor);
			usageCache.set(root, collectedInfoData);
			apply(collectedInfoData, executor);
		}
	}

	private static void apply(IUsageInfoData usageInfoData) {
		long start = System.currentTimeMillis();
		usageInfoData.apply();
		if (LOG.isDebugEnabled()) {
			LOG.debug("Apply usage data in {}ms", System.currentTimeMillis() - start);
		}
	}

	private static void apply(IUsageInfoData usageInfoData, UsageInfoParallelExecutor executor) {
		if (usageInfoData instanceof UsageInfo) {
			// share the epoch executor, UsageInfo applies the serial fallback itself when disabled
			long start = System.currentTimeMillis();
			((UsageInfo) usageInfoData).apply(executor);
			if (LOG.isDebugEnabled()) {
				LOG.debug("Apply usage data in {}ms", System.currentTimeMillis() - start);
			}
		} else {
			apply(usageInfoData);
		}
	}

	static IUsageInfoData buildUsageData(RootNode root, UsageInfoParallelExecutor executor) {
		if (executor.isParallel() && canCollectInParallel(root)) {
			return buildUsageDataParallel(root, executor);
		}
		return buildUsageDataSerial(root);
	}

	static IUsageInfoData buildUsageDataSerial(RootNode root) {
		UsageInfo usageInfo = new UsageInfo(root);
		for (ClassNode cls : root.getClasses()) {
			processClass(cls, usageInfo, SERIAL_ERROR_SINK);
		}
		return usageInfo;
	}

	/**
	 * Per-class parallel collection into worker-local graphs, merged deterministically
	 * in the original class order on the caller thread. Every real class is scanned exactly once,
	 * methods within a class are always processed serially by a single worker.
	 */
	static IUsageInfoData buildUsageDataParallel(RootNode root, UsageInfoParallelExecutor executor) {
		List<ClassNode> classes = root.getClasses();
		List<Shard> shards = buildShards(classes, executor);
		UsageInfo usageInfo = new UsageInfo(root);
		Deque<Future<ShardResult>> inFlight = new ArrayDeque<>();
		int next = 0;
		while (next < shards.size() || !inFlight.isEmpty()) {
			// bounded number of in-flight shards
			while (inFlight.size() < executor.getParallelism() && next < shards.size()) {
				Shard shard = shards.get(next);
				if (shard.isTooBigForLocal() && !inFlight.isEmpty()) {
					// oversize shard scans the global graph directly,
					// it must run only after all earlier shards are merged to keep scan order
					break;
				}
				next++;
				if (shard.isTooBigForLocal()) {
					// oversize class: direct serial scan into the global graph
					processShardSerially(shard, usageInfo);
				} else {
					inFlight.add(executor.submit(() -> runShard(shard, root, executor)));
				}
			}
			if (inFlight.isEmpty()) {
				// remaining shards were all processed inline, nothing to merge
				continue;
			}
			Future<ShardResult> future = inFlight.pollFirst();
			ShardResult result;
			try {
				result = future.get();
			} catch (ExecutionException e) {
				executor.cancelRunningTasks();
				Throwable cause = e.getCause();
				if (cause instanceof Error) {
					throw (Error) cause;
				}
				if (cause instanceof RuntimeException) {
					throw (RuntimeException) cause;
				}
				throw new JadxRuntimeException("Usage info collection failed", cause);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				executor.cancelRunningTasks();
				throw new JadxRuntimeException("Usage info collection interrupted", e);
			}
			mergeShardResult(shards.get(result.getShardIndex()), usageInfo, result, executor, inFlight);
		}
		return usageInfo;
	}

	private static void mergeShardResult(Shard shard, UsageInfo usageInfo, ShardResult result,
			UsageInfoParallelExecutor executor, Deque<Future<ShardResult>> inFlight) {
		Exception fatal = result.getFatal();
		if (fatal != null) {
			// stop all workers, keep the serial-compatible prefix of diagnostics and edges, then rethrow
			executor.cancelRunningTasks();
			for (Future<ShardResult> pending : inFlight) {
				pending.cancel(true);
			}
			registerDiagnostics(result);
			UsageInfo localInfo = result.getLocalInfo();
			if (localInfo != null) {
				// keep partial edges collected before the failure as the serial scan would
				usageInfo.mergeFrom(localInfo);
			}
			if (fatal instanceof RuntimeException) {
				throw (RuntimeException) fatal;
			}
			throw new JadxRuntimeException("Usage info collection failed", fatal);
		}
		if (result.isOversize()) {
			// budget exhausted inside the worker: replay the whole shard serially on the frontier thread
			processShardSerially(shard, usageInfo);
			return;
		}
		if (result.isCancelled()) {
			return;
		}
		usageInfo.mergeFrom(result.getLocalInfo());
		registerDiagnostics(result);
	}

	private static void processShardSerially(Shard shard, UsageInfo usageInfo) {
		for (ClassNode cls : shard.getClasses()) {
			processClass(cls, usageInfo, SERIAL_ERROR_SINK);
		}
	}

	private static void registerDiagnostics(ShardResult result) {
		for (MethodScanError scanError : result.getDiagnostics()) {
			scanError.mth.addError(DEPENDENCY_SCAN_ERROR, scanError.cause);
		}
	}

	private static ShardResult runShard(Shard shard, RootNode root, UsageInfoParallelExecutor executor) {
		if (Thread.currentThread().isInterrupted()) {
			return ShardResult.cancelled(shard.getIndex());
		}
		UsageInfo localInfo = new UsageInfo(root, true, new UsageInfoParallelExecutor.ScanBudget(executor.getShardBudget()));
		List<MethodScanError> diagnostics = new ArrayList<>();
		BiConsumer<MethodNode, Exception> errorSink = (mth, e) -> diagnostics.add(new MethodScanError(mth, e));
		try {
			for (ClassNode cls : shard.getClasses()) {
				processClass(cls, localInfo, errorSink);
			}
			return new ShardResult(shard.getIndex(), localInfo, diagnostics, false, false, null);
		} catch (UsageInfoParallelExecutor.ScanBudgetExceeded e) {
			// internal control: drop partial local graph, the shard is replayed serially
			return new ShardResult(shard.getIndex(), null, Collections.emptyList(), true, false, null);
		} catch (Exception e) {
			return new ShardResult(shard.getIndex(), localInfo, diagnostics, false, false, e);
		}
	}

	private static List<Shard> buildShards(List<ClassNode> classes, UsageInfoParallelExecutor executor) {
		List<Shard> shards = new ArrayList<>();
		List<ClassNode> current = new ArrayList<>();
		long currentEstimate = 0;
		int index = 0;
		for (ClassNode cls : classes) {
			long clsEstimate = estimateClassBytes(cls);
			if (clsEstimate > executor.getShardBudget()) {
				// oversized class gets its own shard, replayed serially without a local graph
				if (!current.isEmpty()) {
					shards.add(new Shard(index++, current, false));
					current = new ArrayList<>();
					currentEstimate = 0;
				}
				List<ClassNode> single = new ArrayList<>(1);
				single.add(cls);
				shards.add(new Shard(index++, single, true));
				continue;
			}
			if (!current.isEmpty()
					&& (current.size() >= MAX_SHARD_CLASSES || currentEstimate + clsEstimate > executor.getShardBudget())) {
				shards.add(new Shard(index++, current, false));
				current = new ArrayList<>();
				currentEstimate = 0;
			}
			current.add(cls);
			currentEstimate += clsEstimate;
		}
		if (!current.isEmpty()) {
			shards.add(new Shard(index, current, false));
		}
		return shards;
	}

	private static long estimateClassBytes(ClassNode cls) {
		long units = 16; // minimal per-class graph overhead
		for (MethodNode mth : cls.getMethods()) {
			ICodeReader reader = mth.getCodeReader();
			if (reader != null) {
				units += reader.getUnitsCount();
			}
		}
		return units * ESTIMATED_BYTES_PER_INSN_UNIT;
	}

	private static boolean canCollectInParallel(RootNode root) {
		List<ClassNode> classes = root.getClasses();
		if (classes.size() < MIN_CLASSES_FOR_PARALLEL) {
			return false;
		}
		long totalUnits = 0;
		for (ClassNode cls : classes) {
			for (MethodNode mth : cls.getMethods()) {
				ICodeReader reader = mth.getCodeReader();
				if (reader == null) {
					continue;
				}
				if (!UsageInfoParallelExecutor.readerSupportsParallelScan(reader)) {
					// unknown/custom reader -> whole collection falls back to serial
					return false;
				}
				totalUnits += reader.getUnitsCount();
			}
		}
		return totalUnits >= MIN_INSN_UNITS_FOR_PARALLEL;
	}

	private static void processClass(ClassNode cls, UsageInfo usageInfo, BiConsumer<MethodNode, Exception> errorSink) {
		usageInfo.clsUse(cls, cls.getSuperClass());
		for (ArgType interfaceType : cls.getInterfaces()) {
			usageInfo.clsUse(cls, interfaceType);
		}
		for (ArgType genericTypeParameter : cls.getGenericTypeParameters()) {
			usageInfo.clsUse(cls, genericTypeParameter);
		}
		for (FieldNode fieldNode : cls.getFields()) {
			usageInfo.clsUse(cls, fieldNode.getType());
			processAnnotations(fieldNode, usageInfo);
			// TODO: process types from field 'constant value'
		}
		processAnnotations(cls, usageInfo);
		for (MethodNode methodNode : cls.getMethods()) {
			processMethod(methodNode, usageInfo, errorSink);
		}
	}

	private static void processMethod(MethodNode mth, UsageInfo usageInfo, BiConsumer<MethodNode, Exception> errorSink) {
		processMethodAnnotations(mth, usageInfo);
		usageInfo.clsUse(mth, mth.getReturnType());
		for (ArgType argType : mth.getArgTypes()) {
			usageInfo.clsUse(mth, argType);
		}
		// TODO: process exception classes from 'throws'
		try {
			processInstructions(mth, usageInfo);
		} catch (Exception e) {
			errorSink.accept(mth, e);
		}
	}

	private static void processInstructions(MethodNode mth, UsageInfo usageInfo) {
		if (mth.isNoCode()) {
			return;
		}
		ICodeReader codeReader = mth.getCodeReader();
		if (codeReader == null) {
			return;
		}
		RootNode root = mth.root();
		codeReader.visitInstructions(insnData -> {
			try {
				processInsn(root, mth, insnData, usageInfo);
			} catch (Exception e) {
				throw new JadxRuntimeException(
						"Usage info collection failed with error: " + e.getMessage() + " at insn: " + insnData, e);
			}
		});
	}

	private static void processInsn(RootNode root, MethodNode mth, InsnData insnData, UsageInfo usageInfo) {
		if (insnData.getOpcode() == Opcode.UNKNOWN) {
			return;
		}
		switch (insnData.getIndexType()) {
			case TYPE_REF:
				insnData.decode();
				ArgType usedType = ArgType.parse(insnData.getIndexAsType());
				usageInfo.clsUse(mth, usedType);
				break;

			case FIELD_REF:
				insnData.decode();
				FieldNode fieldNode = root.resolveField(FieldInfo.fromRef(root, insnData.getIndexAsField()));
				if (fieldNode != null) {
					usageInfo.fieldUse(mth, fieldNode);
				}
				break;

			case METHOD_REF: {
				insnData.decode();
				IMethodRef mthRef;
				ICustomPayload payload = insnData.getPayload();
				if (payload != null) {
					mthRef = (IMethodRef) payload;
				} else {
					mthRef = insnData.getIndexAsMethod();
				}
				MethodInfo mthInfo = MethodInfo.fromRef(root, mthRef);
				MethodNode methodNode = root.resolveMethod(mthInfo);
				if (methodNode != null) {
					usageInfo.methodUse(mth, methodNode);
				} else {
					usageInfo.unresolvedMethodUse(mth, mthInfo);
				}
				break;
			}

			case CALL_SITE: {
				insnData.decode();
				ICallSite callSite = InsnDataUtils.getCallSite(insnData);
				IMethodHandle methodHandle = InsnDataUtils.getMethodHandleAt(callSite, 4);
				if (methodHandle != null) {
					IMethodRef mthRef = methodHandle.getMethodRef();
					if (mthRef != null) {
						MethodInfo mthInfo = MethodInfo.fromRef(root, mthRef);
						MethodNode mthNode = root.resolveMethod(mthInfo);
						if (mthNode != null) {
							usageInfo.methodUse(mth, mthNode);
						} else {
							usageInfo.unresolvedMethodUse(mth, mthInfo);
						}
					}
				}
				break;
			}
		}
	}

	private static void processAnnotations(ICodeNode node, UsageInfo usageInfo) {
		AnnotationsAttr annAttr = node.get(JadxAttrType.ANNOTATION_LIST);
		processAnnotationAttr(node, annAttr, usageInfo);
	}

	private static void processMethodAnnotations(MethodNode mth, UsageInfo usageInfo) {
		processAnnotations(mth, usageInfo);
		AnnotationMethodParamsAttr paramsAttr = mth.get(JadxAttrType.ANNOTATION_MTH_PARAMETERS);
		if (paramsAttr != null) {
			for (AnnotationsAttr annAttr : paramsAttr.getParamList()) {
				processAnnotationAttr(mth, annAttr, usageInfo);
			}
		}
	}

	private static void processAnnotationAttr(ICodeNode node, AnnotationsAttr annAttr, UsageInfo usageInfo) {
		if (annAttr == null || annAttr.isEmpty()) {
			return;
		}
		for (IAnnotation ann : annAttr.getList()) {
			processAnnotation(node, ann, usageInfo);
		}
	}

	private static void processAnnotation(ICodeNode node, IAnnotation ann, UsageInfo usageInfo) {
		usageInfo.clsUse(node, ArgType.parse(ann.getAnnotationClass()));
		for (EncodedValue value : ann.getValues().values()) {
			processAnnotationValue(node, value, usageInfo);
		}
	}

	@SuppressWarnings("unchecked")
	private static void processAnnotationValue(ICodeNode node, EncodedValue value, UsageInfo usageInfo) {
		Object obj = value.getValue();
		switch (value.getType()) {
			case ENCODED_TYPE:
				usageInfo.clsUse(node, ArgType.parse((String) obj));
				break;
			case ENCODED_ENUM:
			case ENCODED_FIELD:
				if (obj instanceof IFieldRef) {
					usageInfo.fieldUse(node, FieldInfo.fromRef(node.root(), (IFieldRef) obj));
				} else if (obj instanceof FieldInfo) {
					usageInfo.fieldUse(node, (FieldInfo) obj);
				} else {
					throw new JadxRuntimeException("Unexpected field type class: " + value.getClass());
				}
				break;
			case ENCODED_ARRAY:
				for (EncodedValue encodedValue : (List<EncodedValue>) obj) {
					processAnnotationValue(node, encodedValue, usageInfo);
				}
				break;
			case ENCODED_ANNOTATION:
				processAnnotation(node, (IAnnotation) obj, usageInfo);
				break;
		}
	}

	public static void replaceMethodUsage(MethodNode mergeIntoMth, MethodNode sourceMth) {
		mergeIntoMth.mergeUseIn(sourceMth);
	}

	@Override
	public String getName() {
		return "UsageInfoVisitor";
	}

	/**
	 * Recoverable instruction scan error deferred to the caller thread to keep diagnostics order.
	 */
	private static final class MethodScanError {
		final MethodNode mth;
		final Exception cause;

		MethodScanError(MethodNode mth, Exception cause) {
			this.mth = mth;
			this.cause = cause;
		}
	}

	/**
	 * A unit of class-exclusive collection work: an ordered group of whole classes.
	 */
	private static final class Shard {
		private final int index;
		private final List<ClassNode> classes;
		private final boolean tooBigForLocal;

		Shard(int index, List<ClassNode> classes, boolean tooBigForLocal) {
			this.index = index;
			this.classes = classes;
			this.tooBigForLocal = tooBigForLocal;
		}

		int getIndex() {
			return index;
		}

		List<ClassNode> getClasses() {
			return classes;
		}

		boolean isTooBigForLocal() {
			return tooBigForLocal;
		}
	}

	/**
	 * Result of a worker-local shard collection: local graph, ordered diagnostics and failure markers.
	 */
	private static final class ShardResult {
		private final int shardIndex;
		private final @Nullable UsageInfo localInfo;
		private final List<MethodScanError> diagnostics;
		private final boolean oversize;
		private final boolean cancelled;
		private final @Nullable Exception fatal;

		ShardResult(int shardIndex, @Nullable UsageInfo localInfo, List<MethodScanError> diagnostics,
				boolean oversize, boolean cancelled, @Nullable Exception fatal) {
			this.shardIndex = shardIndex;
			this.localInfo = localInfo;
			this.diagnostics = diagnostics;
			this.oversize = oversize;
			this.cancelled = cancelled;
			this.fatal = fatal;
		}

		static ShardResult cancelled(int shardIndex) {
			return new ShardResult(shardIndex, null, Collections.emptyList(), false, true, null);
		}

		int getShardIndex() {
			return shardIndex;
		}

		@Nullable
		UsageInfo getLocalInfo() {
			return localInfo;
		}

		List<MethodScanError> getDiagnostics() {
			return diagnostics;
		}

		boolean isOversize() {
			return oversize;
		}

		boolean isCancelled() {
			return cancelled;
		}

		@Nullable
		Exception getFatal() {
			return fatal;
		}
	}
}
