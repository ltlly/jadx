package jadx.core.dex.visitors.usage;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jadx.api.usage.IUsageInfoData;
import jadx.api.usage.IUsageInfoVisitor;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for parallel usage info collection/publish:
 * serial and parallel pipelines must produce identical published usage state
 * (same lists, same order, same re-resolution semantics).
 */
class UsageInfoVisitorParallelTest extends SmaliTest {

	private static final String SMALI_DIR = "src/test/smali/others/TestUsageInfoParallel";

	@BeforeEach
	@Override
	public void init() {
		super.init();
		// harness default is 1 thread, allow the parallel executor to start workers
		getArgs().setThreadsCount(8);
	}

	private static List<File> getSmaliFiles() {
		File dir = new File(SMALI_DIR);
		File[] files = dir.listFiles((d, name) -> name.endsWith(".smali"));
		assertThat(files).as("Smali fixture not found in " + dir).isNotNull();
		return Arrays.stream(files).collect(Collectors.toList());
	}

	private RootNode loadRoot(boolean parallel) {
		setParallelEnabled(parallel);
		jadxDecompiler = loadFiles(getSmaliFiles());
		return jadxDecompiler.getRoot();
	}

	private static void setParallelEnabled(boolean enabled) {
		if (enabled) {
			System.clearProperty(UsageInfoParallelExecutor.PARALLEL_PROPERTY);
		} else {
			System.setProperty(UsageInfoParallelExecutor.PARALLEL_PROPERTY, "false");
		}
	}

	@Test
	void parallelApplyPublishesSameListsAsSerial() {
		RootNode serialRoot = loadRoot(false);
		try {
			RootNode parallelRoot = loadRoot(true);
			assertFixtureGraphShape(serialRoot);
			assertPublishedUsageEquals(serialRoot, parallelRoot);
		} finally {
			setParallelEnabled(true);
		}
	}

	@Test
	void publicApplyUsesParallelExecutorWhenEnabled() {
		RootNode serialRoot = loadRoot(false);
		try {
			RootNode parallelRoot = loadRoot(true);
			// rebuild the graph serially and publish through the public apply() with the switch ON
			IUsageInfoData freshData = UsageInfoVisitor.buildUsageDataSerial(parallelRoot);
			setParallelEnabled(true);
			freshData.apply();
			assertFixtureGraphShape(serialRoot);
			assertPublishedUsageEquals(serialRoot, parallelRoot);
		} finally {
			setParallelEnabled(true);
		}
	}

	@Test
	void parallelCollectMatchesSerialOracle() {
		RootNode root = loadRoot(true);
		UsageInfoParallelExecutor executor = UsageInfoParallelExecutor.open(getArgs());
		try {
			assertThat(executor.isParallel()).isTrue();
			IUsageInfoData serialData = UsageInfoVisitor.buildUsageDataSerial(root);
			IUsageInfoData parallelData = UsageInfoVisitor.buildUsageDataParallel(root, executor);
			assertThat(snapshotUsageData(parallelData)).isEqualTo(snapshotUsageData(serialData));
		} finally {
			executor.close();
		}
	}

	@Test
	void oversizeShardFallsBackToSerialReplay() {
		RootNode root = loadRoot(true);
		IUsageInfoData serialData = UsageInfoVisitor.buildUsageDataSerial(root);
		UsageInfoParallelExecutor executor = UsageInfoParallelExecutor.open(getArgs());
		try {
			// tiny budget forces both oversize pre-check replays and in-task budget fallbacks
			executor.setShardBudget(1024);
			IUsageInfoData parallelData = UsageInfoVisitor.buildUsageDataParallel(root, executor);
			assertThat(snapshotUsageData(parallelData)).isEqualTo(snapshotUsageData(serialData));
		} finally {
			executor.close();
		}
	}

	@Test
	void killSwitchDisablesParallelExecutor() {
		RootNode root = loadRoot(false);
		IUsageInfoData serialData = UsageInfoVisitor.buildUsageDataSerial(root);
		try {
			System.setProperty(UsageInfoParallelExecutor.PARALLEL_PROPERTY, "false");
			UsageInfoParallelExecutor executor = UsageInfoParallelExecutor.open(getArgs());
			try {
				assertThat(executor.isParallel()).isFalse();
				// buildUsageData with a serial executor must use the legacy serial path
				IUsageInfoData builtData = UsageInfoVisitor.buildUsageData(root, executor);
				assertThat(snapshotUsageData(builtData)).isEqualTo(snapshotUsageData(serialData));
			} finally {
				executor.close();
			}
			// unknown values are treated as disabled
			System.setProperty(UsageInfoParallelExecutor.PARALLEL_PROPERTY, "banana");
			assertThat(UsageInfoParallelExecutor.open(getArgs()).isParallel()).isFalse();
		} finally {
			setParallelEnabled(true);
		}
	}

	/**
	 * Verify the fixture produces the non-trivial graph features the equivalence checks rely on:
	 * self-recursion, unresolved refs, inner-class method usage, overload resolution,
	 * inherited method resolution and field usage.
	 */
	private static void assertFixtureGraphShape(RootNode root) {
		ClassNode aa = findCls(root, "upa.Aa");
		MethodNode recurse = findMth(aa, "recurse(I)I");
		assertThat(recurse.callsSelf()).as("recurse is self-recursive").isTrue();
		assertThat(mthIds(recurse.getUseIn())).as("recurse called from inner class method")
				.contains(mthId(findCls(root, "upa.BB$In").getMethods().get(1)));

		MethodNode callMissing = findMth(aa, "callMissing()V");
		assertThat(unresolvedIds(callMissing.getUnresolvedUsed()))
				.as("unresolved method ref is collected")
				.anySatisfy(id -> assertThat(id).contains("Missing.gone"));

		ClassNode bb = findCls(root, "upa.BB");
		MethodNode util = findMth(bb, "util(I)I");
		List<String> utilUseIn = mthIds(util.getUseIn());
		assertThat(utilUseIn).as("direct call resolution").contains("upa.Aa.ifMth()I");
		assertThat(utilUseIn).as("inherited call resolution").contains("upa.Dd.inheritedUse(I)I");

		FieldNode shared = bb.getFields().stream()
				.filter(fld -> fld.getName().equals("shared"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("shared field not found"));
		assertThat(mthIds(shared.getUseIn())).as("field usage").contains("upa.Aa.ifMth()I");

		List<String> aaUseInMth = mthIds(aa.getUseInMth());
		assertThat(aaUseInMth).as("class use in outer method from inner class")
				.contains("upa.BB$In.callOuter(I)I");
	}

	private static ClassNode findCls(RootNode root, String rawName) {
		return root.getClasses().stream()
				.filter(cls -> cls.getRawName().equals(rawName))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Class not found: " + rawName));
	}

	private static MethodNode findMth(ClassNode cls, String shortId) {
		return cls.getMethods().stream()
				.filter(mth -> mth.getMethodInfo().getShortId().equals(shortId))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Method not found: " + shortId));
	}

	private static String mthId(MethodNode mth) {
		return mth.getMethodInfo().getRawFullId();
	}

	/**
	 * Published node state must be element-wise identical: same lists, same order,
	 * same current-node identity and re-resolution results.
	 */
	private static void assertPublishedUsageEquals(RootNode serialRoot, RootNode parallelRoot) {
		List<ClassNode> serialClasses = serialRoot.getClasses();
		List<ClassNode> parallelClasses = parallelRoot.getClasses();
		List<String> serialNames = serialClasses.stream().map(ClassNode::getRawName).collect(Collectors.toList());
		List<String> parallelNames = parallelClasses.stream().map(ClassNode::getRawName).collect(Collectors.toList());
		assertThat(parallelNames).containsExactlyElementsOf(serialNames);

		for (int i = 0; i < serialClasses.size(); i++) {
			ClassNode serialCls = serialClasses.get(i);
			ClassNode parallelCls = parallelClasses.get(i);
			String clsDesc = parallelCls.getRawName();

			assertThat(ids(parallelCls.getDependencies()))
					.as("%s dependencies", clsDesc)
					.containsExactlyElementsOf(ids(serialCls.getDependencies()));
			assertThat(topIds(parallelCls.getUseIn()))
					.as("%s useIn", clsDesc)
					.containsExactlyElementsOf(topIds(serialCls.getUseIn()));
			assertThat(mthIds(parallelCls.getUseInMth()))
					.as("%s useInMth", clsDesc)
					.containsExactlyElementsOf(mthIds(serialCls.getUseInMth()));

			List<FieldNode> serialFields = serialCls.getFields();
			List<FieldNode> parallelFields = parallelCls.getFields();
			assertThat(parallelFields.size()).isEqualTo(serialFields.size());
			for (int f = 0; f < serialFields.size(); f++) {
				FieldNode serialFld = serialFields.get(f);
				FieldNode parallelFld = parallelFields.get(f);
				assertThat(parallelFld.getFieldInfo().getRawFullId())
						.isEqualTo(serialFld.getFieldInfo().getRawFullId());
				assertThat(mthIds(parallelFld.getUseIn()))
						.as("%s field %s useIn", clsDesc, serialFld.getName())
						.containsExactlyElementsOf(mthIds(serialFld.getUseIn()));
			}

			List<MethodNode> serialMethods = serialCls.getMethods();
			List<MethodNode> parallelMethods = parallelCls.getMethods();
			assertThat(parallelMethods.size()).isEqualTo(serialMethods.size());
			for (int m = 0; m < serialMethods.size(); m++) {
				MethodNode serialMth = serialMethods.get(m);
				MethodNode parallelMth = parallelMethods.get(m);
				assertThat(parallelMth.getMethodInfo().getRawFullId())
						.isEqualTo(serialMth.getMethodInfo().getRawFullId());
				String mthDesc = parallelMth.getMethodInfo().getRawFullId();

				assertThat(mthIds(parallelMth.getUseIn()))
						.as("%s useIn", mthDesc)
						.containsExactlyElementsOf(mthIds(serialMth.getUseIn()));
				assertThat(unresolvedIds(parallelMth.getUnresolvedUsed()))
						.as("%s unresolvedUsed", mthDesc)
						.containsExactlyElementsOf(unresolvedIds(serialMth.getUnresolvedUsed()));
				assertThat(parallelMth.callsSelf())
						.as("%s callsSelf", mthDesc)
						.isEqualTo(serialMth.callsSelf());
				// outgoing usage is a Set: compare content
				assertThat(mthIds(parallelMth.getUsed()))
						.as("%s used", mthDesc)
						.containsExactlyInAnyOrderElementsOf(mthIds(serialMth.getUsed()));
				// re-resolution: published nodes are the current nodes of the parallel root
				for (MethodNode caller : parallelMth.getUseIn()) {
					assertThat(caller).isSameAs(parallelRoot.resolveDirectMethod(
							caller.getParentClass().getRawName(), caller.getMethodInfo().getShortId()));
				}
				for (MethodNode usedMth : parallelMth.getUsed()) {
					assertThat(usedMth).isSameAs(parallelRoot.resolveDirectMethod(
							usedMth.getParentClass().getRawName(), usedMth.getMethodInfo().getShortId()));
				}
			}
		}
	}

	/**
	 * Snapshot the full usage graph (sorted and re-resolved values, as consumed by cache/GUI)
	 * into an ordered map of raw id lists.
	 */
	private static Map<String, List<String>> snapshotUsageData(IUsageInfoData data) {
		Map<String, List<String>> snapshot = new TreeMap<>();
		data.visitUsageData(new IUsageInfoVisitor() {
			@Override
			public void visitClassDeps(ClassNode cls, List<ClassNode> deps) {
				snapshot.put("deps:" + cls.getRawName(), ids(deps));
			}

			@Override
			public void visitClassUsage(ClassNode cls, List<ClassNode> usage) {
				snapshot.put("use:" + cls.getRawName(), ids(usage));
			}

			@Override
			public void visitClassUseInMethods(ClassNode cls, List<MethodNode> methods) {
				snapshot.put("useInMth:" + cls.getRawName(), mthIds(methods));
			}

			@Override
			public void visitFieldsUsage(FieldNode fld, List<MethodNode> methods) {
				snapshot.put("fld:" + fld.getFieldInfo().getRawFullId(), mthIds(methods));
			}

			@Override
			public void visitMethodsUsage(MethodNode mth, List<MethodNode> methods) {
				snapshot.put("useMth:" + mth.getMethodInfo().getRawFullId(), mthIds(methods));
			}

			@Override
			public void visitMethodsUses(MethodNode mth, List<MethodNode> methods) {
				snapshot.put("uses:" + mth.getMethodInfo().getRawFullId(), mthIds(methods));
			}

			@Override
			public void visitUnresolvedMethodsUsage(MethodNode mth, List<MethodInfo> methods) {
				snapshot.put("unres:" + mth.getMethodInfo().getRawFullId(), unresolvedIds(methods));
			}

			@Override
			public void visitIsSelfCall(MethodNode mth, boolean isSelfCall) {
				snapshot.put("self:" + mth.getMethodInfo().getRawFullId(), List.of(Boolean.toString(isSelfCall)));
			}

			@Override
			public void visitComplete() {
				// no-op
			}
		});
		return snapshot;
	}

	private static List<String> ids(List<ClassNode> classes) {
		return classes.stream().map(ClassNode::getRawName).collect(Collectors.toList());
	}

	private static List<String> topIds(List<ClassNode> classes) {
		return classes.stream().map(cls -> cls.getTopParentClass().getRawName()).collect(Collectors.toList());
	}

	private static List<String> mthIds(List<MethodNode> methods) {
		return methods.stream().map(mth -> mth.getMethodInfo().getRawFullId()).collect(Collectors.toList());
	}

	private static List<String> mthIds(Iterable<MethodNode> methods) {
		List<String> ids = new ArrayList<>();
		for (MethodNode mth : methods) {
			ids.add(mth.getMethodInfo().getRawFullId());
		}
		return ids;
	}

	private static List<String> unresolvedIds(List<MethodInfo> methods) {
		return methods.stream().map(MethodInfo::getRawFullId).collect(Collectors.toList());
	}
}
