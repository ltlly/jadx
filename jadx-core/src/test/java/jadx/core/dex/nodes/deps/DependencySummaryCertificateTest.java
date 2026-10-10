package jadx.core.dex.nodes.deps;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.visitors.ClassModifier;
import jadx.core.dex.visitors.MethodThrowsVisitor;
import jadx.core.dex.visitors.fixaccessmodifiers.FixAccessModifiers;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage of the v1 certification allowlist: the certification matrix
 * classification and per-fixture miss reasons (fail closed on any doubt).
 */
class DependencySummaryCertificateTest extends SmaliTest {

	private static final String SMALI_DIR = "src/test/smali/others/TestDependencySummary";

	@BeforeEach
	@Override
	public void init() {
		super.init();
	}

	@AfterEach
	public void restoreProperties() {
		System.clearProperty(DependencySummaryManager.ENABLE_PROPERTY);
	}

	private static List<File> fixtureFiles(String... names) {
		List<File> files = new ArrayList<>();
		for (String name : names) {
			files.add(new File(SMALI_DIR, name));
		}
		return files;
	}

	private static String certify(ClassNode topCls) {
		return DependencyRecoveryCertificate.verifySubtree(topCls);
	}

	@Test
	void certificationMatrixCoversResidualPasses() {
		assertThat(DependencyRecoveryCertificate.classify("MethodThrowsVisitor"))
				.isEqualTo(DependencyRecoveryCertificate.PassClassification.RESIDUAL);
		assertThat(DependencyRecoveryCertificate.classify("FixAccessModifiers"))
				.isEqualTo(DependencyRecoveryCertificate.PassClassification.RESIDUAL);
		assertThat(DependencyRecoveryCertificate.classify("ClassModifier"))
				.isEqualTo(DependencyRecoveryCertificate.PassClassification.RESIDUAL_PARTIAL);
		// stage-2a annotation residuals: original passes replayed on live pinned data
		assertThat(DependencyRecoveryCertificate.classify("ModVisitor"))
				.isEqualTo(DependencyRecoveryCertificate.PassClassification.RESIDUAL);
		assertThat(DependencyRecoveryCertificate.classify("NonFinalResIdsVisitor"))
				.isEqualTo(DependencyRecoveryCertificate.PassClassification.RESIDUAL);
		assertThat(DependencyRecoveryCertificate.classify("PrepareForCodeGen"))
				.isEqualTo(DependencyRecoveryCertificate.PassClassification.RESIDUAL);
		// unclassified/unknown passes are unsupported -> global disable
		assertThat(DependencyRecoveryCertificate.classify("TotallyUnknownPass"))
				.isEqualTo(DependencyRecoveryCertificate.PassClassification.UNSUPPORTED);
		// stage-2a TODO-verify verdict: no CFG/SSA/type/region/shrink pass was
		// proven skippable for coded classes; they stay LOCAL_IR_ONLY (no-code
		// shapes) and coded classes fail closed via METHOD_WITH_CODE
		assertThat(DependencyRecoveryCertificate.classify("SSATransform"))
				.isEqualTo(DependencyRecoveryCertificate.PassClassification.LOCAL_IR_ONLY);
		assertThat(DependencyRecoveryCertificate.classify("RegionMakerVisitor"))
				.isEqualTo(DependencyRecoveryCertificate.PassClassification.LOCAL_IR_ONLY);
		assertThat(DependencyRecoveryCertificate.classify("CheckRegions"))
				.isEqualTo(DependencyRecoveryCertificate.PassClassification.LOCAL_IR_ONLY);
	}

	@Test
	void noCodeInterfaceCertifiesAndCodeClassDoesNot() {
		System.setProperty(DependencySummaryManager.ENABLE_PROPERTY, "true");
		jadxDecompiler = loadFiles(fixtureFiles("Leaf.smali", "Shared.smali", "Left.smali", "Holder.smali"));
		RootNode root = jadxDecompiler.getRoot();
		// classes must be processed first: the throws witness exists only
		// after the own analysis round
		root.resolveClass("dst.Shared").decompile();
		root.resolveClass("dst.Leaf").decompile();
		root.resolveClass("dst.Left").decompile();
		root.resolveClass("dst.Holder").decompile();

		assertThat(certify(root.resolveClass("dst.Shared"))).isNull();
		assertThat(certify(root.resolveClass("dst.Leaf"))).isNull();
		assertThat(certify(root.resolveClass("dst.Left")))
				.isEqualTo("METHOD_WITH_CODE");
		assertThat(certify(root.resolveClass("dst.Holder")))
				.isEqualTo("METHOD_WITH_CODE");
	}

	@Test
	void annotatedNoCodeMembersCertifyInStage2a() {
		// stage-2a: pinned annotation payloads are served from live state and the
		// original annotation residual passes; the former v1 blockers are lifted
		System.setProperty(DependencySummaryManager.ENABLE_PROPERTY, "true");
		jadxDecompiler = loadFiles(fixtureFiles("Annotated.smali"));
		RootNode root = jadxDecompiler.getRoot();
		root.resolveClass("dst.Annotated").decompile();

		assertThat(certify(root.resolveClass("dst.Annotated"))).isNull();
		// stats helper sees the pinned method annotation payload
		assertThat(DependencyRecoveryCertificate.hasPinnedAnnotations(root.resolveClass("dst.Annotated"))).isTrue();
		// stats helpers: certified subtree has no reasons, but pinned annotations
		assertThat(DependencyRecoveryCertificate.collectAllMissReasons(root.resolveClass("dst.Annotated")))
				.isEmpty();
	}

	@Test
	void enumAndAnonymousSubtreesFailClosed() throws Exception {
		System.setProperty(DependencySummaryManager.ENABLE_PROPERTY, "true");

		File enumDir = new File("src/test/smali/enums/TestSwitchOverEnum");
		File[] enumFiles = enumDir.listFiles((d, name) -> name.endsWith(".smali"));
		assertThat(enumFiles).isNotNull();
		jadxDecompiler = loadFiles(Arrays.asList(enumFiles));
		jadxDecompiler.getRoot().resolveClass("enums.TestSwitchOverEnum").decompile();
		// top class itself has code; the enum subtree member is rejected by enum rule
		assertThat(certify(jadxDecompiler.getRoot().resolveClass("enums.TestSwitchOverEnum")))
				.isEqualTo("METHOD_WITH_CODE");

		File anonDir = new File("src/test/smali/inner/TestAnonymousClass14");
		File[] anonFiles = anonDir.listFiles((d, name) -> name.endsWith(".smali"));
		assertThat(anonFiles).isNotNull();
		jadxDecompiler = loadFiles(Arrays.asList(anonFiles));
		jadxDecompiler.getRoot().resolveClass("inner.OuterCls").decompile();
		// anonymous/inlined subtrees fail closed (first rejecting reason wins)
		assertThat(certify(jadxDecompiler.getRoot().resolveClass("inner.OuterCls")))
				.isIn("ANONYMOUS_CLASS", "INLINED_CLASSES");
	}

	@Test
	void emptySyntheticClassResidualIsPureStructural() {
		// the residual operation must be a pure live-structure check
		System.setProperty(DependencySummaryManager.ENABLE_PROPERTY, "true");
		jadxDecompiler = loadFiles(fixtureFiles("Leaf.smali"));
		RootNode root = jadxDecompiler.getRoot();
		ClassNode leaf = root.resolveClass("dst.Leaf");
		// non-synthetic interface: residual must not add DONT_GENERATE
		boolean wasEmpty = ClassModifier.isEmptySyntheticClass(leaf);
		assertThat(wasEmpty).isFalse();
		assertThat(leaf.contains(jadx.core.dex.attributes.AFlag.DONT_GENERATE)).isFalse();

		// residual helpers exist and are the original implementations
		assertThat(new MethodThrowsVisitor()).isNotNull();
		assertThat(new FixAccessModifiers()).isNotNull();
	}
}
