package jadx.core.dex.nodes.deps;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import jadx.api.JadxDecompiler;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.ClassTypeVarsAttr;
import jadx.core.dex.attributes.nodes.MethodThrowsAttr;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.ProcessState;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.visitors.IDexTreeVisitor;
import jadx.core.dex.visitors.PrepareForCodeGen;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for recovery-equivalent dependency summaries:
 * the published state observed by a consumer after a certified summary
 * recovery must be equal to the full legacy re-process result
 * (process(dep, false) after GENERATED_AND_UNLOADED).
 *
 * Summary equivalence tests drive the real generated-unload recovery path
 * through explicit class decompile order on fresh decompilers. Boundary checks
 * also exercise metadata-only reads and a deliberately unsafe resident control.
 */
class DependencySummaryTest extends SmaliTest {

	private static final String SMALI_DIR = "src/test/smali/others/TestDependencySummary";

	@BeforeEach
	@Override
	public void init() {
		super.init();
	}

	@AfterEach
	public void restoreProperties() {
		System.clearProperty(DependencySummaryManager.ENABLE_PROPERTY);
		System.clearProperty(DependencySummaryManager.STRICT_PROPERTY);
		System.clearProperty(DependencySummaryManager.STATS_PROPERTY);
	}

	private static List<File> fixtureFiles(String... names) {
		List<File> files = new ArrayList<>();
		for (String name : names) {
			files.add(new File(SMALI_DIR, name));
		}
		return files;
	}

	private static List<File> dirFiles(String dir) {
		File d = new File(dir);
		File[] files = d.listFiles((x, name) -> name.endsWith(".smali"));
		assertThat(files).as("fixture dir " + dir).isNotNull();
		return Arrays.asList(files);
	}

	private JadxDecompiler loadWithSummaries(List<File> files) {
		System.setProperty(DependencySummaryManager.ENABLE_PROPERTY, "true");
		// the harness shares JadxArgs across loads: reset the code cache so every
		// fresh decompiler really re-processes classes
		getArgs().setCodeCache(new jadx.api.impl.InMemoryCodeCache());
		jadxDecompiler = loadFiles(files);
		jadxDecompiler.getRoot().getDependencySummaryManager().beginEpoch();
		return jadxDecompiler;
	}

	private JadxDecompiler loadLegacy(List<File> files) {
		System.setProperty(DependencySummaryManager.ENABLE_PROPERTY, "false");
		getArgs().setCodeCache(new jadx.api.impl.InMemoryCodeCache());
		jadxDecompiler = loadFiles(files);
		return jadxDecompiler;
	}

	private JadxDecompiler reloadWithSummaries(List<File> files) {
		System.setProperty(DependencySummaryManager.ENABLE_PROPERTY, "true");
		getArgs().setCodeCache(new jadx.api.impl.InMemoryCodeCache());
		jadxDecompiler = loadFiles(files);
		jadxDecompiler.getRoot().getDependencySummaryManager().beginEpoch();
		return jadxDecompiler;
	}

	private static void endEpoch(JadxDecompiler decompiler) {
		decompiler.getRoot().getDependencySummaryManager().endEpoch();
	}

	private static ClassNode cls(RootNode root, String fullName) {
		ClassNode node = root.resolveClass(fullName);
		assertThat(node).as("class " + fullName).isNotNull();
		return node;
	}

	/**
	 * Reader projection of the published class state: state/attrs/flags/aliases,
	 * ordered member and usage lists, throws lists with visited marker and
	 * diagnostics. Deliberately excludes body residency (expected difference).
	 */
	private static Map<String, String> snapshot(RootNode root) {
		Map<String, String> map = new TreeMap<>();
		for (ClassNode cls : root.getClasses()) {
			if (cls.isInner()) {
				continue;
			}
			for (ClassNode part : cls.getTopParentClass().selfAndInnerClasses()) {
				snapshotClass(map, part);
			}
		}
		return map;
	}

	private static void snapshotClass(Map<String, String> map, ClassNode cls) {
		StringBuilder sb = new StringBuilder();
		sb.append("state=").append(cls.getState().name()).append(';');
		sb.append("loadStage=").append(cls.getLoadStage()).append(';');
		sb.append("access=").append(cls.getAccessFlags().rawValue()).append(';');
		sb.append("attrs=").append(cls.getAttributesString()).append(';');
		sb.append("deps=").append(rawNames(cls.getDependencies())).append(';');
		sb.append("codegenDeps=").append(rawNames(cls.getCodegenDeps())).append(';');
		sb.append("useIn=").append(rawNames(cls.getUseIn())).append(';');
		sb.append("useInMth=").append(mthIds(cls.getUseInMth())).append(';');
		map.put("cls:" + cls.getFullName(), sb.toString());
		for (MethodNode mth : cls.getMethods()) {
			StringBuilder msb = new StringBuilder();
			msb.append("access=").append(mth.getAccessFlags().rawValue()).append(';');
			msb.append("attrs=").append(mth.getAttributesString()).append(';');
			msb.append("useIn=").append(mthIds(mth.getUseIn())).append(';');
			MethodThrowsAttr throwsAttr = mth.get(AType.METHOD_THROWS);
			if (throwsAttr != null) {
				msb.append("throwsVisited=").append(throwsAttr.isVisited()).append(';');
				msb.append("throwsList=").append(throwList(throwsAttr)).append(';');
			}
			map.put("mth:" + cls.getFullName() + '#' + mth.getMethodInfo().makeSignature(true), msb.toString());
		}
		for (FieldNode fld : cls.getFields()) {
			StringBuilder fsb = new StringBuilder();
			fsb.append("access=").append(fld.getAccessFlags().rawValue()).append(';');
			fsb.append("attrs=").append(fld.getAttributesString()).append(';');
			fsb.append("useIn=").append(mthIds(fld.getUseIn())).append(';');
			map.put("fld:" + cls.getFullName() + '#' + fld.getName(), fsb.toString());
		}
	}

	private static List<String> rawNames(List<ClassNode> classes) {
		List<String> list = new ArrayList<>(classes.size());
		for (ClassNode cls : classes) {
			list.add(cls.getRawName());
		}
		return list;
	}

	private static List<String> mthIds(List<MethodNode> methods) {
		List<String> list = new ArrayList<>(methods.size());
		for (MethodNode mth : methods) {
			list.add(mth.getParentClass().getRawName() + '#' + mth.getMethodInfo().makeSignature(true));
		}
		return list;
	}

	private static String throwList(MethodThrowsAttr attr) {
		// HashSet iteration order is stable within one JVM for equal contents
		String[] arr = attr.getList().toArray(new String[0]);
		Arrays.sort(arr);
		return Arrays.toString(arr);
	}

	private static void assertPublishedStateEquals(RootNode legacyRoot, RootNode summaryRoot) {
		Map<String, String> legacySnapshot = snapshot(legacyRoot);
		Map<String, String> summarySnapshot = snapshot(summaryRoot);
		assertThat(summarySnapshot).as("published state projection").isEqualTo(legacySnapshot);
	}

	@Test
	void sharedDependencyRecoveryMatchesLegacy() {
		List<File> files = fixtureFiles("Leaf.smali", "Shared.smali", "Left.smali", "Right.smali");

		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		ClassNode lShared = cls(legacyRoot, "dst.Shared");
		lShared.decompile();
		ClassNode lLeft = cls(legacyRoot, "dst.Left");
		lLeft.decompile();
		ClassNode lRight = cls(legacyRoot, "dst.Right");
		lRight.decompile();
		// legacy: recovered dep has resident body
		assertThat(lShared.getState().isProcessComplete()).isTrue();
		assertThat(lShared.getMethods().get(0).isLoaded()).isTrue();
		String lSharedCode = lShared.getCode().getCodeStr();
		String lLeftCode = lLeft.getCode().getCodeStr();
		String lRightCode = lRight.getCode().getCodeStr();

		JadxDecompiler on = loadWithSummaries(files);
		RootNode onRoot = on.getRoot();
		ClassNode sShared = cls(onRoot, "dst.Shared");
		sShared.decompile();
		DependencyAnalysisSummary summary = sShared.getDependencySummary();
		assertThat(summary).as("summary prepared for Shared").isNotNull();
		assertThat(summary.isPrepared()).isTrue();
		cls(onRoot, "dst.Left").decompile();
		// first re-entry must use the certified summary recovery
		DependencySummaryManager manager = onRoot.getDependencySummaryManager();
		assertThat(manager.getSummaryRecoveries()).as("summary recoveries").isEqualTo(1);
		assertThat(summary.isRecovered()).isTrue();
		// summary-only residency: no body re-decode
		assertThat(sShared.getMethods().get(0).isLoaded()).isFalse();
		assertThat(sShared.getState().isProcessComplete()).isTrue();
		cls(onRoot, "dst.Right").decompile();
		// second consumer observes the recovered state, no additional recovery
		assertThat(manager.getSummaryRecoveries()).as("summary recoveries after 2nd consumer").isEqualTo(1);

		endEpoch(on);
		assertThat(manager.getPreparedCount()).isGreaterThanOrEqualTo(1);
		String sSharedCode = sShared.getCode().getCodeStr();
		String sLeftCode = cls(onRoot, "dst.Left").getCode().getCodeStr();
		String sRightCode = cls(onRoot, "dst.Right").getCode().getCodeStr();

		assertPublishedStateEquals(legacyRoot, onRoot);
		// generated code of all classes is byte-identical
		assertThat(sSharedCode).isEqualTo(lSharedCode);
		assertThat(sLeftCode).isEqualTo(lLeftCode);
		assertThat(sRightCode).isEqualTo(lRightCode);
	}

	@Test
	void throwsInheritanceKeepsVisitedFreeze() {
		List<File> files = fixtureFiles("Sig.smali", "SigUser.smali");

		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		ClassNode lSig = cls(legacyRoot, "dst.Sig");
		lSig.decompile();
		cls(legacyRoot, "dst.SigUser").decompile();
		String lSigCode = lSig.getCode().getCodeStr();
		String lUserCode = cls(legacyRoot, "dst.SigUser").getCode().getCodeStr();

		JadxDecompiler on = loadWithSummaries(files);
		RootNode onRoot = on.getRoot();
		ClassNode sSig = cls(onRoot, "dst.Sig");
		sSig.decompile();
		cls(onRoot, "dst.SigUser").decompile();
		endEpoch(on);
		String sSigCode = sSig.getCode().getCodeStr();
		String sUserCode = cls(onRoot, "dst.SigUser").getCode().getCodeStr();

		DependencySummaryManager manager = onRoot.getDependencySummaryManager();
		assertThat(manager.getSummaryRecoveries()).isEqualTo(1);
		assertPublishedStateEquals(legacyRoot, onRoot);

		// pinned visited must stay true and list contents identical, including
		// invalid/unresolved declared exceptions handling
		for (int i = 0; i < lSig.getMethods().size(); i++) {
			MethodNode legacyMth = lSig.getMethods().get(i);
			MethodNode summaryMth = sSig.getMethods().get(i);
			MethodThrowsAttr legacyAttr = legacyMth.get(AType.METHOD_THROWS);
			MethodThrowsAttr summaryAttr = summaryMth.get(AType.METHOD_THROWS);
			assertThat(summaryAttr).isNotNull();
			assertThat(summaryAttr.isVisited()).isEqualTo(legacyAttr.isVisited());
			assertThat(throwList(summaryAttr)).isEqualTo(throwList(legacyAttr));
			assertThat(summaryMth.getThrows()).isEqualTo(legacyMth.getThrows());
		}
		assertThat(sSigCode).isEqualTo(lSigCode);
		assertThat(sUserCode).isEqualTo(lUserCode);
	}

	@Test
	void genericOverrideUsesCurrentMappings() {
		List<File> files = fixtureFiles("GenericBase.smali", "Derived.smali", "GenericUser.smali");

		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		cls(legacyRoot, "dst.Derived").decompile();
		cls(legacyRoot, "dst.GenericUser").decompile();
		String lUserCode = cls(legacyRoot, "dst.GenericUser").getCode().getCodeStr();

		JadxDecompiler on = loadWithSummaries(files);
		RootNode onRoot = on.getRoot();
		cls(onRoot, "dst.Derived").decompile();
		cls(onRoot, "dst.GenericUser").decompile();
		endEpoch(on);
		String sUserCode = cls(onRoot, "dst.GenericUser").getCode().getCodeStr();

		DependencySummaryManager manager = onRoot.getDependencySummaryManager();
		// v1 must fall back for generic classes, no certified recovery happened
		assertThat(manager.getSummaryRecoveries()).isEqualTo(0);
		assertThat(manager.getMissReasons().get("METHOD_WITH_CODE")).isNotNull();
		assertPublishedStateEquals(legacyRoot, onRoot);
		assertThat(sUserCode).isEqualTo(lUserCode);
	}

	@Test
	void anonymousAndSingletonBodiesRemainLegacy() {
		List<File> files = dirFiles("src/test/smali/inner/TestAnonymousClass14");

		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		ClassNode lOuter = cls(legacyRoot, "inner.OuterCls");
		lOuter.decompile();
		String lCode = lOuter.getCode().getCodeStr();

		JadxDecompiler on = loadWithSummaries(files);
		RootNode onRoot = on.getRoot();
		ClassNode sOuter = cls(onRoot, "inner.OuterCls");
		sOuter.decompile();
		endEpoch(on);
		String sCode = sOuter.getCode().getCodeStr();

		// anonymous classes must never certify
		assertThat(onRoot.getDependencySummaryManager().getSummaryRecoveries()).isEqualTo(0);
		assertPublishedStateEquals(legacyRoot, onRoot);
		assertThat(sCode).isEqualTo(lCode);
		// no missed body errors may appear
		assertThat(sCode).doesNotContain("Method arg registers not loaded");
	}

	@Test
	void inlineAndLateUsageEffectsAreNotDeduped() {
		List<File> files = fixtureFiles("Holder.smali");

		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		ClassNode lHolder = cls(legacyRoot, "dst.Holder");
		lHolder.decompile();
		String lCode = lHolder.getCode().getCodeStr();

		JadxDecompiler on = loadWithSummaries(files);
		RootNode onRoot = on.getRoot();
		ClassNode sHolder = cls(onRoot, "dst.Holder");
		sHolder.decompile();
		endEpoch(on);
		String sCode = sHolder.getCode().getCodeStr();

		// code methods with possible inline effects stay legacy
		assertThat(onRoot.getDependencySummaryManager().getSummaryRecoveries()).isEqualTo(0);
		assertPublishedStateEquals(legacyRoot, onRoot);
		assertThat(sCode).isEqualTo(lCode);
	}

	@Test
	void enumOrdinalAndRemappedSwitch() {
		List<File> files = dirFiles("src/test/smali/enums/TestSwitchOverEnum");

		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		ClassNode lEnum = cls(legacyRoot, "enums.TestSwitchOverEnum");
		lEnum.decompile();
		String lCode = lEnum.getCode().getCodeStr();

		JadxDecompiler on = loadWithSummaries(files);
		RootNode onRoot = on.getRoot();
		ClassNode sEnum = cls(onRoot, "enums.TestSwitchOverEnum");
		sEnum.decompile();
		endEpoch(on);
		String sCode = sEnum.getCode().getCodeStr();

		// enums fall back in v1
		assertThat(onRoot.getDependencySummaryManager().getSummaryRecoveries()).isEqualTo(0);
		assertPublishedStateEquals(legacyRoot, onRoot);
		assertThat(sCode).isEqualTo(lCode);
	}

	@Test
	void unresolvedRefsDoNotBecomeSuccessOrLoseWarnings() {
		List<File> files = fixtureFiles("MissingRefs.smali", "Consts.smali");

		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		cls(legacyRoot, "dst.Consts").decompile();
		ClassNode lMissing = cls(legacyRoot, "dst.MissingRefs");
		lMissing.decompile();
		String lMissingCode = lMissing.getCode().getCodeStr();
		String lConstsCode = cls(legacyRoot, "dst.Consts").getCode().getCodeStr();
		int legacyErrors = legacy.getErrorsCount();
		int legacyWarns = legacy.getWarnsCount();

		JadxDecompiler on = loadWithSummaries(files);
		RootNode onRoot = on.getRoot();
		cls(onRoot, "dst.Consts").decompile();
		ClassNode sMissing = cls(onRoot, "dst.MissingRefs");
		sMissing.decompile();
		endEpoch(on);
		String sMissingCode = sMissing.getCode().getCodeStr();
		String sConstsCode = cls(onRoot, "dst.Consts").getCode().getCodeStr();

		assertPublishedStateEquals(legacyRoot, onRoot);
		// same unresolved warnings/errors with identical multiplicity
		assertThat(sMissingCode).isEqualTo(lMissingCode);
		assertThat(sConstsCode).isEqualTo(lConstsCode);
		assertThat(on.getErrorsCount()).isEqualTo(legacyErrors);
		assertThat(on.getWarnsCount()).isEqualTo(legacyWarns);
	}

	@Test
	@Timeout(60)
	void selfAndMutualDependencyDontWait() {
		List<File> files = fixtureFiles("Ping.smali", "Pong.smali");

		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		ClassNode lPing = cls(legacyRoot, "dst.Ping");
		lPing.decompile();
		cls(legacyRoot, "dst.Pong").decompile();
		String lPingCode = lPing.getCode().getCodeStr();

		JadxDecompiler on = loadWithSummaries(files);
		RootNode onRoot = on.getRoot();
		ClassNode sPing = cls(onRoot, "dst.Ping");
		sPing.decompile();
		cls(onRoot, "dst.Pong").decompile();
		endEpoch(on);
		String sPingCode = sPing.getCode().getCodeStr();

		// cycle members have code -> legacy fallback, no deadlock
		assertThat(onRoot.getDependencySummaryManager().getSummaryRecoveries()).isEqualTo(0);
		assertPublishedStateEquals(legacyRoot, onRoot);
		assertThat(sPingCode).isEqualTo(lPingCode);
	}

	@Test
	void twoOrdersSamePublishedStateOnCertifiedFixture() {
		List<File> files = fixtureFiles("Leaf.smali", "Shared.smali", "Left.smali", "Right.smali");

		// order 1: Left then Right
		JadxDecompiler on1 = loadWithSummaries(files);
		RootNode root1 = on1.getRoot();
		cls(root1, "dst.Shared").decompile();
		cls(root1, "dst.Left").decompile();
		cls(root1, "dst.Right").decompile();
		endEpoch(on1);
		String left1 = cls(root1, "dst.Left").getCode().getCodeStr();
		String right1 = cls(root1, "dst.Right").getCode().getCodeStr();
		Map<String, String> snap1 = snapshot(root1);

		// order 2: Right then Left (dependency inputs identical and pre-frozen)
		JadxDecompiler on2 = reloadWithSummaries(files);
		RootNode root2 = on2.getRoot();
		cls(root2, "dst.Shared").decompile();
		cls(root2, "dst.Right").decompile();
		cls(root2, "dst.Left").decompile();
		endEpoch(on2);
		String left2 = cls(root2, "dst.Left").getCode().getCodeStr();
		String right2 = cls(root2, "dst.Right").getCode().getCodeStr();
		Map<String, String> snap2 = snapshot(root2);

		// legacy oracle
		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		cls(legacyRoot, "dst.Shared").decompile();
		cls(legacyRoot, "dst.Left").decompile();
		cls(legacyRoot, "dst.Right").decompile();
		String leftL = cls(legacyRoot, "dst.Left").getCode().getCodeStr();
		String rightL = cls(legacyRoot, "dst.Right").getCode().getCodeStr();

		assertThat(snap1).isEqualTo(snap2);
		assertPublishedStateEquals(legacyRoot, root1);
		assertThat(left1).isEqualTo(leftL);
		assertThat(right1).isEqualTo(rightL);
		assertThat(left2).isEqualTo(leftL);
		assertThat(right2).isEqualTo(rightL);
	}

	@Test
	void genericMetadataReadDoesNotRequireBodyRecovery() {
		JadxDecompiler legacy = loadLegacy(fixtureFiles("GenericBase.smali", "GenericDerived.smali"));
		RootNode root = legacy.getRoot();
		ClassNode base = cls(root, "dst.GenericBase");
		ClassNode derived = cls(root, "dst.Derived");
		ClassTypeVarsAttr before = root.getTypeUtils().getClassTypeVars(derived.getType());
		assertThat(before).isNotNull();
		Map<ArgType, ArgType> expected = new HashMap<>(before.getTypeVarsMapFor(base.getType()));
		assertThat(expected.values()).containsExactly(ArgType.STRING);

		derived.decompile();
		assertThat(derived.getState()).isEqualTo(ProcessState.GENERATED_AND_UNLOADED);
		assertThat(derived.get(AType.CLASS_TYPE_VARS)).isNull();
		for (MethodNode mth : derived.getMethods()) {
			assertThat(mth.isLoaded()).isFalse();
		}
		int errors = legacy.getErrorsCount();
		int warnings = legacy.getWarnsCount();

		// This is a lazy metadata read, not a dependency re-entry or summary recovery.
		ClassTypeVarsAttr rebuilt = root.getTypeUtils().getClassTypeVars(derived.getType());
		assertThat(rebuilt).isNotNull().isNotSameAs(before);
		assertThat(rebuilt.getTypeVarsMapFor(base.getType())).isEqualTo(expected);
		assertThat(derived.getState()).isEqualTo(ProcessState.GENERATED_AND_UNLOADED);
		for (MethodNode mth : derived.getMethods()) {
			assertThat(mth.isLoaded()).isFalse();
		}
		assertThat(legacy.getErrorsCount()).isEqualTo(errors);
		assertThat(legacy.getWarnsCount()).isEqualTo(warnings);
	}

	@Test
	void retainingCodedDependencyDropsAnnotationEffects() {
		List<File> files = fixtureFiles("Consts.smali", "AnnotatedCodedData.smali", "AnnotatedDataUser.smali");

		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		ClassNode legacyData = cls(legacyRoot, "dst.AnnotatedData");
		assertThat(legacyData.getMethods().get(0).isNoCode()).isFalse();
		legacyData.decompile();
		assertThat(legacyData.getState()).isEqualTo(ProcessState.GENERATED_AND_UNLOADED);
		cls(legacyRoot, "dst.AnnotatedDataUser").decompile();
		assertThat(legacyData.getState()).isEqualTo(ProcessState.PROCESS_COMPLETE);
		List<String> legacyUses = mthIds(cls(legacyRoot, "dst.Consts").getFields().get(0).getUseIn());
		// JadxArgs is shared by this harness; freeze oracle output before resetting its cache.
		String legacyDataCode = legacyData.getCode().getCodeStr();
		String legacyUserCode = cls(legacyRoot, "dst.AnnotatedDataUser").getCode().getCodeStr();
		int legacyErrors = legacy.getErrorsCount();
		int legacyWarnings = legacy.getWarnsCount();

		JadxDecompiler resident = loadLegacy(files);
		RootNode residentRoot = resident.getRoot();
		ClassNode residentData = cls(residentRoot, "dst.AnnotatedData");
		residentData.add(AFlag.DONT_UNLOAD_CLASS);
		residentData.decompile();
		residentData.remove(AFlag.DONT_UNLOAD_CLASS);
		assertThat(residentData.getState()).isEqualTo(ProcessState.PROCESS_COMPLETE);
		cls(residentRoot, "dst.AnnotatedDataUser").decompile();
		List<String> residentUses = mthIds(cls(residentRoot, "dst.Consts").getFields().get(0).getUseIn());

		// Retaining the body skips the second annotation-use publication even
		// though the cached Java outputs happen to match in this fixture.
		assertThat(residentUses).containsExactly("dst.AnnotatedData#read()Ljava/lang/String;");
		assertThat(legacyUses).containsExactly(residentUses.get(0), residentUses.get(0));
		assertThat(residentData.getCode().getCodeStr()).isEqualTo(legacyDataCode);
		assertThat(cls(residentRoot, "dst.AnnotatedDataUser").getCode().getCodeStr()).isEqualTo(legacyUserCode);
		assertThat(resident.getErrorsCount()).isEqualTo(legacyErrors);
		assertThat(resident.getWarnsCount()).isEqualTo(legacyWarnings);
	}

	@Test
	void codedAnnotationClassEffectCanBeReplayedWithoutBodyRecovery() throws Exception {
		List<File> files = fixtureFiles("Consts.smali", "AnnotatedCodedData.smali", "AnnotatedDataUser.smali");
		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		ClassNode legacyData = cls(legacyRoot, "dst.AnnotatedData");
		legacyData.decompile();
		legacyRoot.getProcessClasses().forceProcess(legacyData);
		assertThat(legacyData.getState()).isEqualTo(ProcessState.PROCESS_COMPLETE);
		List<String> expectedUses = mthIds(cls(legacyRoot, "dst.Consts").getFields().get(0).getUseIn());

		JadxDecompiler effectOnly = loadLegacy(files);
		RootNode effectRoot = effectOnly.getRoot();
		ClassNode effectData = cls(effectRoot, "dst.AnnotatedData");
		effectData.decompile();
		PrepareForCodeGen prepare = null;
		for (IDexTreeVisitor pass : effectRoot.getProcessClasses().getPasses()) {
			if (pass instanceof PrepareForCodeGen) {
				prepare = (PrepareForCodeGen) pass;
				break;
			}
		}
		assertThat(prepare).isNotNull();
		// Direct callback probe: compare one class-level effect against LEGACY
		// forceProcess, not complete metadata-dependency recovery.
		prepare.visit(effectData);
		assertThat(mthIds(cls(effectRoot, "dst.Consts").getFields().get(0).getUseIn())).isEqualTo(expectedUses);
		assertThat(effectData.getState()).isEqualTo(ProcessState.GENERATED_AND_UNLOADED);
		for (MethodNode mth : effectData.getMethods()) {
			assertThat(mth.isLoaded()).isFalse();
		}
		assertThat(effectOnly.getErrorsCount()).isEqualTo(legacy.getErrorsCount());
		assertThat(effectOnly.getWarnsCount()).isEqualTo(legacy.getWarnsCount());
	}

	@Test
	void annotatedNoCodeClassRecoveryMatchesLegacy() {
		// stage-2a: pinned annotation payloads are served from live state plus the
		// original annotation residual passes (Mod class visit, NonFinalResIds,
		// PrepareForCodeGen class/field annotation usage)
		List<File> files = fixtureFiles("Consts.smali", "AnnotatedData.smali", "AnnotatedDataUser.smali");
		System.setProperty(DependencySummaryManager.STATS_PROPERTY, "true");

		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		ClassNode lData = cls(legacyRoot, "dst.AnnotatedData");
		lData.decompile();
		cls(legacyRoot, "dst.AnnotatedDataUser").decompile();
		String lDataCode = lData.getCode().getCodeStr();
		String lUserCode = cls(legacyRoot, "dst.AnnotatedDataUser").getCode().getCodeStr();
		int legacyErrors = legacy.getErrorsCount();
		int legacyWarns = legacy.getWarnsCount();

		JadxDecompiler on = loadWithSummaries(files);
		RootNode onRoot = on.getRoot();
		ClassNode sData = cls(onRoot, "dst.AnnotatedData");
		sData.decompile();
		assertThat(sData.getDependencySummary()).as("summary prepared for annotated class").isNotNull();
		cls(onRoot, "dst.AnnotatedDataUser").decompile();
		endEpoch(on);

		DependencySummaryManager manager = onRoot.getDependencySummaryManager();
		assertThat(manager.getSummaryRecoveries()).as("annotated no-code recovery").isEqualTo(1);
		assertThat(manager.getAnnotationRecoveries()).as("annotation residual recoveries").isEqualTo(1);
		// the former v1 annotation blockers are gone
		assertThat(manager.getMissReasons()).doesNotContainKeys(
				"CLASS_ANNOTATIONS", "FIELD_ANNOTATIONS", "METHOD_ANNOTATIONS");

		assertPublishedStateEquals(legacyRoot, onRoot);
		assertThat(sData.getCode().getCodeStr()).isEqualTo(lDataCode);
		assertThat(cls(onRoot, "dst.AnnotatedDataUser").getCode().getCodeStr()).isEqualTo(lUserCode);
		// identical diagnostics multiplicity (annotation residual warnings included)
		assertThat(on.getErrorsCount()).isEqualTo(legacyErrors);
		assertThat(on.getWarnsCount()).isEqualTo(legacyWarns);

		// stats line exposes the stage-2a coverage fields
		String stats = manager.formatStatsLine();
		assertThat(stats).contains("annotation-recoveries=1");
		assertThat(stats).contains("reentries=1");
	}

	@Test
	void codedDiamondFallsBackToFullLegacyReprocess() {
		// stage-2a fail-closed verdict: no CFG/SSA/type/region/shrink pass was
		// proven skippable for coded methods, so coded classes keep the full
		// legacy re-process; the certificate must not claim them
		List<File> files = fixtureFiles(
				"CodedShared.smali", "CodedLeft.smali", "CodedRight.smali", "CodedTop.smali");

		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		ClassNode lShared = cls(legacyRoot, "dst.CodedShared");
		lShared.decompile();
		cls(legacyRoot, "dst.CodedLeft").decompile();
		cls(legacyRoot, "dst.CodedRight").decompile();
		cls(legacyRoot, "dst.CodedTop").decompile();
		String lSharedCode = lShared.getCode().getCodeStr();
		String lLeftCode = cls(legacyRoot, "dst.CodedLeft").getCode().getCodeStr();
		String lRightCode = cls(legacyRoot, "dst.CodedRight").getCode().getCodeStr();
		String lTopCode = cls(legacyRoot, "dst.CodedTop").getCode().getCodeStr();

		JadxDecompiler on = loadWithSummaries(files);
		RootNode onRoot = on.getRoot();
		DependencySummaryManager manager = onRoot.getDependencySummaryManager();
		ClassNode sShared = cls(onRoot, "dst.CodedShared");
		sShared.decompile();
		// prepare must fail closed on the coded method, before any consumer runs
		assertThat(sShared.getDependencySummary()).as("no summary for coded class").isNull();
		assertThat(manager.getMissReasons().get("METHOD_WITH_CODE")).isNotNull();
		cls(onRoot, "dst.CodedLeft").decompile();
		// exactly one legacy fallback re-entry, later consumers reuse residency
		assertThat(manager.getFallbackReentries()).isEqualTo(1);
		cls(onRoot, "dst.CodedRight").decompile();
		cls(onRoot, "dst.CodedTop").decompile();
		endEpoch(on);

		assertThat(manager.getSummaryRecoveries()).isEqualTo(0);
		assertThat(sShared.getMethods().get(0).isLoaded()).isTrue();
		assertPublishedStateEquals(legacyRoot, onRoot);
		assertThat(sShared.getCode().getCodeStr()).isEqualTo(lSharedCode);
		assertThat(cls(onRoot, "dst.CodedLeft").getCode().getCodeStr()).isEqualTo(lLeftCode);
		assertThat(cls(onRoot, "dst.CodedRight").getCode().getCodeStr()).isEqualTo(lRightCode);
		assertThat(cls(onRoot, "dst.CodedTop").getCode().getCodeStr()).isEqualTo(lTopCode);
	}

	@Test
	void reentryMissBitmaskAndPerReasonTime() {
		List<File> files = fixtureFiles("Leaf.smali", "Shared.smali", "Left.smali", "Right.smali");
		System.setProperty(DependencySummaryManager.STATS_PROPERTY, "true");

		JadxDecompiler on = loadWithSummaries(files);
		RootNode onRoot = on.getRoot();
		DependencySummaryManager manager = onRoot.getDependencySummaryManager();
		cls(onRoot, "dst.Shared").decompile();
		endEpoch(on);
		// a new epoch invalidates the prepared summary only at re-entry
		onRoot.getDependencySummaryManager().beginEpoch();

		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		cls(legacyRoot, "dst.Shared").decompile();
		cls(legacyRoot, "dst.Left").decompile();
		String lLeftCode = cls(legacyRoot, "dst.Left").getCode().getCodeStr();

		System.setProperty(DependencySummaryManager.ENABLE_PROPERTY, "true");
		getArgs().setCodeCache(new jadx.api.impl.InMemoryCodeCache());
		cls(onRoot, "dst.Left").decompile();
		endEpoch(on);

		assertThat(manager.getSummaryRecoveries()).isEqualTo(0);
		java.util.concurrent.atomic.LongAdder bitmask = manager.getMissBitmasks().get("EPOCH_MISMATCH");
		assertThat(bitmask).as("multi-reason bitmask counter").isNotNull();
		assertThat(bitmask.sum()).isEqualTo(1);
		java.util.concurrent.atomic.LongAdder reasonNanos = manager.getMissReasonNanos().get("EPOCH_MISMATCH");
		assertThat(reasonNanos).as("per-reason fallback time").isNotNull();
		assertThat(reasonNanos.sum()).isGreaterThan(0L);
		String stats = manager.formatStatsLine();
		assertThat(stats).contains("miss-bitmasks=[EPOCH_MISMATCH:1]");
		assertThat(stats).contains("miss-time-ms=[EPOCH_MISMATCH:");
		assertThat(stats).contains("verify-time-ms=");
		assertThat(stats).contains("replay-time-ms=");
		assertPublishedStateEquals(legacyRoot, onRoot);
		assertThat(cls(onRoot, "dst.Left").getCode().getCodeStr()).isEqualTo(lLeftCode);
	}

	@Test
	void budgetOrMissingSummaryFallsBackBeforeMutation() {
		List<File> files = fixtureFiles("Leaf.smali", "Shared.smali", "Left.smali", "Right.smali");

		System.setProperty(DependencySummaryManager.ENABLE_PROPERTY, "true");
		getArgs().setCodeCache(new jadx.api.impl.InMemoryCodeCache());
		jadxDecompiler = loadFiles(files);
		RootNode onRoot = jadxDecompiler.getRoot();
		DependencySummaryManager manager = onRoot.getDependencySummaryManager();
		manager.setRetainedBudgetForTest(0); // tiny budget forces reserve failure
		manager.beginEpoch();
		ClassNode sShared = cls(onRoot, "dst.Shared");
		sShared.decompile();
		// prepare had to fall back on budget reserve
		assertThat(sShared.getDependencySummary()).isNull();
		assertThat(manager.getMissReasons().get("BUDGET_EXCEEDED")).isNotNull();
		cls(onRoot, "dst.Left").decompile();
		cls(onRoot, "dst.Right").decompile();
		endEpoch(jadxDecompiler);
		assertThat(manager.getSummaryRecoveries()).isEqualTo(0);

		// legacy oracle: same full rounds, effects and state
		JadxDecompiler legacy = loadLegacy(files);
		RootNode legacyRoot = legacy.getRoot();
		cls(legacyRoot, "dst.Shared").decompile();
		cls(legacyRoot, "dst.Left").decompile();
		cls(legacyRoot, "dst.Right").decompile();
		assertPublishedStateEquals(legacyRoot, onRoot);
	}

	@Test
	void killSwitchAndReloadAreLegacy() {
		List<File> files = fixtureFiles("Leaf.smali", "Shared.smali", "Left.smali", "Right.smali");

		// property absent -> disabled
		JadxDecompiler off = loadLegacy(files);
		RootNode offRoot = off.getRoot();
		assertThat(offRoot.getDependencySummaryManager().isEnabled()).isFalse();
		offRoot.getDependencySummaryManager().beginEpoch();
		assertThat(offRoot.getDependencySummaryManager().isEpochActive()).isFalse();
		cls(offRoot, "dst.Shared").decompile();
		cls(offRoot, "dst.Left").decompile();
		// no summaries allocated at all
		assertThat(cls(offRoot, "dst.Shared").getDependencySummary()).isNull();
		assertThat(offRoot.getDependencySummaryManager().getPreparedCount()).isEqualTo(0);
		assertThat(offRoot.getDependencySummaryManager().getSummaryRecoveries()).isEqualTo(0);

		// invalid value treated as disabled
		System.setProperty(DependencySummaryManager.ENABLE_PROPERTY, "bogus");
		getArgs().setCodeCache(new jadx.api.impl.InMemoryCodeCache());
		JadxDecompiler bogus = loadFiles(files);
		assertThat(bogus.getRoot().getDependencySummaryManager().isEnabled()).isFalse();

		// explicit reload invalidates summaries before processing
		JadxDecompiler on = reloadWithSummaries(files);
		RootNode onRoot = on.getRoot();
		ClassNode sShared = cls(onRoot, "dst.Shared");
		sShared.decompile();
		cls(onRoot, "dst.Left").decompile();
		assertThat(sShared.getDependencySummary()).isNotNull();
		onRoot.getDependencySummaryManager().invalidateAll();
		assertThat(sShared.getDependencySummary()).isNull();
		// a fresh epoch is a new one, stale summaries are not reused
		long firstEpoch = onRoot.getDependencySummaryManager().getEpochId();
		onRoot.getDependencySummaryManager().beginEpoch();
		assertThat(onRoot.getDependencySummaryManager().getEpochId()).isNotEqualTo(firstEpoch);
		endEpoch(on);
	}
}
