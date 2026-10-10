package jadx.core.dex.nodes.deps;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxDecompiler;
import jadx.api.impl.InMemoryCodeCache;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.nodes.deps.DependencyProcessingProfile.Span;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DependencyProcessingProfileIntegrationTest extends SmaliTest {

	@TempDir
	Path temp;

	private String previousEnable;
	private String previousOutput;

	@BeforeEach
	@Override
	public void init() {
		super.init();
		getArgs().setRunDebugChecks(false);
		previousEnable = System.getProperty(DependencyProcessingProfile.ENABLE_PROPERTY);
		previousOutput = System.getProperty(DependencyProcessingProfile.OUTPUT_PROPERTY);
		System.clearProperty(DependencyProcessingProfile.ENABLE_PROPERTY);
		System.clearProperty(DependencyProcessingProfile.OUTPUT_PROPERTY);
	}

	@AfterEach
	void restoreProperties() {
		restore(DependencyProcessingProfile.ENABLE_PROPERTY, previousEnable);
		restore(DependencyProcessingProfile.OUTPUT_PROPERTY, previousOutput);
	}

	private static void restore(String property, String value) {
		if (value == null) {
			System.clearProperty(property);
		} else {
			System.setProperty(property, value);
		}
	}

	private JadxDecompiler loadFixture() {
		List<File> files = new ArrayList<>();
		for (String name : new String[] { "CodedShared", "CodedLeft", "CodedRight", "CodedTop" }) {
			files.add(new File("src/test/smali/others/TestDependencySummary/" + name + ".smali"));
		}
		getArgs().setCodeCache(new InMemoryCodeCache());
		jadxDecompiler = loadFiles(files);
		return jadxDecompiler;
	}

	private static Map<String, String> run(RootNode root) {
		Map<String, String> result = new TreeMap<>();
		for (String name : new String[] { "CodedShared", "CodedLeft", "CodedRight", "CodedTop" }) {
			ClassNode cls = root.resolveClass("dst." + name);
			assertThat(cls).isNotNull();
			cls.decompile();
			result.put(name + ":code", cls.getCode().getCodeStr());
		}
		for (ClassNode cls : root.getClasses()) {
			result.put(cls.getRawName() + ":state", cls.getState() + ":" + cls.getLoadStage());
			result.put(cls.getRawName() + ":attrs", cls.getAttributesString());
			result.put(cls.getRawName() + ":access", "" + cls.getAccessFlags().rawValue());
			result.put(cls.getRawName() + ":uses", cls.getUseIn() + ":" + cls.getUseInMth());
			for (MethodNode mth : cls.getMethods()) {
				String id = cls.getRawName() + '#' + mth.getMethodInfo().makeSignature(true);
				result.put(id, mth.getAttributesString() + ":" + mth.getAccessFlags().rawValue() + ":" + mth.isLoaded());
			}
		}
		return result;
	}

	@Test
	void missingArgumentsStillThrowWithoutRecoveringBody() throws Exception {
		System.setProperty(DependencyProcessingProfile.ENABLE_PROPERTY, "true");
		JadxDecompiler enabled = loadFixture();
		RootNode root = enabled.getRoot();
		ClassNode shared = root.resolveClass("dst.CodedShared");
		shared.decompile();
		MethodNode mth = shared.getMethods().get(0);
		assertThat(mth.isLoaded()).isFalse();
		DependencyProcessingProfile profile = root.getDependencyProcessingProfile();
		try (Span span = profile.begin(root.resolveClass("dst.CodedLeft"), "MISSING_ARGUMENTS", false, false)) {
			assertThatThrownBy(mth::getArgRegs).isInstanceOf(jadx.core.utils.exceptions.JadxRuntimeException.class);
		}
		assertThat(mth.isLoaded()).isFalse();
		profile.writeReport(temp);
		String[] read = Files.readAllLines(temp.resolve("body-reads.tsv")).stream()
				.filter(line -> line.startsWith("MISSING_ARGUMENTS\t"))
				.findFirst().orElseThrow().split("\t");
		assertThat(read[7]).isEqualTo("false");
	}

	@Test
	void diagnosticWriteFailureDoesNotReplaceOutputOrFailClose() throws Exception {
		Path existing = temp.resolve("existing");
		Files.writeString(existing, "preserve");
		System.setProperty(DependencyProcessingProfile.ENABLE_PROPERTY, "true");
		System.setProperty(DependencyProcessingProfile.OUTPUT_PROPERTY, existing.toString());
		JadxDecompiler enabled = loadFixture();
		run(enabled.getRoot());
		enabled.close();
		jadxDecompiler = null;
		assertThat(Files.readString(existing)).isEqualTo("preserve");
	}

	@Test
	void optInProfilePreservesFixtureCodeStateAndDiagnostics() throws Exception {
		JadxDecompiler baseline = loadFixture();
		assertThat(baseline.getRoot().getDependencyProcessingProfile()).isNull();
		Map<String, String> expected = run(baseline.getRoot());
		int errors = baseline.getErrorsCount();
		int warnings = baseline.getWarnsCount();

		System.setProperty(DependencyProcessingProfile.ENABLE_PROPERTY, "true");
		JadxDecompiler enabled = loadFixture();
		RootNode root = enabled.getRoot();
		DependencyProcessingProfile profile = root.getDependencyProcessingProfile();
		assertThat(profile).isNotNull();
		assertThat(run(root)).isEqualTo(expected);
		assertThat(enabled.getErrorsCount()).isEqualTo(errors);
		assertThat(enabled.getWarnsCount()).isEqualTo(warnings);

		ClassNode shared = root.resolveClass("dst.CodedShared");
		MethodNode mth = shared.getMethods().get(0);
		Object blocks = mth.getBasicBlocks();
		Object region = mth.getRegion();
		Object arguments = mth.getArgRegs();
		Object instructions = mth.getInstructions();
		try (Span span = profile.begin(root.resolveClass("dst.CodedLeft"), "MANUAL_READER", false, false)) {
			assertThat(mth.getBasicBlocks()).isSameAs(blocks);
			assertThat(mth.getRegion()).isSameAs(region);
			assertThat(mth.getArgRegs()).isSameAs(arguments);
			assertThat(mth.getInstructions()).isSameAs(instructions);
		}
		profile.writeReport(temp);
		assertThat(Files.readAllLines(temp.resolve("body-reads.tsv")).stream()
				.filter(line -> line.startsWith("MANUAL_READER\t")).count()).isEqualTo(4L);
		assertThat(Files.readAllLines(temp.resolve("decode-attempts.tsv")).stream()
				.anyMatch(line -> line.startsWith("LOAD_CLASS\ttrue\t"))).isTrue();
		assertThat(Files.readAllLines(temp.resolve("stages.tsv")).stream()
				.anyMatch(line -> line.startsWith("PROCESS/METADATA_DEPENDENCY/false\ttrue\t"))).isTrue();
		String[] status = Files.readAllLines(temp.resolve("status.tsv")).get(1).split("\t");
		assertThat(status[0]).isEqualTo("0");
		assertThat(status[1]).isEqualTo("0");
	}
}
