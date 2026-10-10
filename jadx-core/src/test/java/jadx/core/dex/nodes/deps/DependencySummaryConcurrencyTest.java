package jadx.core.dex.nodes.deps;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.ProcessState;
import jadx.core.dex.nodes.RootNode;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency behavior of summary publication: many save workers may observe a
 * dependency at the same time, but exactly one certified recovery runs and no
 * concurrent duplicate publication happens.
 */
class DependencySummaryConcurrencyTest extends SmaliTest {

	private static final String SMALI_DIR = "src/test/smali/others/TestDependencySummary";

	@TempDir
	Path tempDir;

	@BeforeEach
	@Override
	public void init() {
		super.init();
		// allow the real multi-thread save executor to run workers
		getArgs().setThreadsCount(8);
		getArgs().setOutDir(tempDir.toFile());
		getArgs().setSkipResources(true);
	}

	@AfterEach
	public void restoreProperties() {
		System.clearProperty(DependencySummaryManager.ENABLE_PROPERTY);
		System.clearProperty(DependencySummaryManager.STRICT_PROPERTY);
	}

	private static List<File> fixtureFiles(String... names) {
		List<File> files = new ArrayList<>();
		for (String name : names) {
			files.add(new File(SMALI_DIR, name));
		}
		return files;
	}

	@Test
	void eightWorkersPublishOnce() {
		// one shared dependency plus eight consumers forces concurrent
		// process(dep, METADATA_DEPENDENCY) calls from eight save tasks
		List<File> files = fixtureFiles("Leaf.smali", "Shared.smali", "Left.smali");
		files.add(consumerFile("C1"));
		files.add(consumerFile("C2"));
		files.add(consumerFile("C3"));
		files.add(consumerFile("C4"));
		files.add(consumerFile("C5"));
		files.add(consumerFile("C6"));
		files.add(consumerFile("C7"));
		files.add(consumerFile("C8"));

		System.setProperty(DependencySummaryManager.ENABLE_PROPERTY, "true");
		jadxDecompiler = loadFiles(files);
		RootNode root = jadxDecompiler.getRoot();
		DependencySummaryManager manager = root.getDependencySummaryManager();
		AtomicInteger observerRuns = new AtomicInteger();
		manager.setRecoveryObserver(observerRuns::incrementAndGet);
		manager.beginEpoch();

		// The scheduler need not generate Shared before its consumers.
		// Establish the recovery precondition rather than depending on worker timing.
		ClassNode shared = root.resolveClass("dst.Shared");
		shared.decompile();
		assertThat(shared.getState()).isEqualTo(ProcessState.GENERATED_AND_UNLOADED);
		assertThat(shared.getDependencySummary()).isNotNull();

		// full save pipeline: real scheduler, eight worker threads, epoch hooks
		jadxDecompiler.save(1000, (done, total) -> {
			// progress not needed
		});

		manager.endEpoch();
		assertThat(observerRuns.get())
				.as("exactly one residual recovery across all workers")
				.isEqualTo(1);
		assertThat(manager.getSummaryRecoveries()).isEqualTo(1);
		assertThat(shared.getDependencySummary().isRecovered()).isTrue();
		assertThat(shared.getState().isProcessComplete()).isTrue();
		// consumers were saved with identical code semantics
		assertThat(jadxDecompiler.getErrorsCount())
				.as("no errors introduced by concurrent recovery")
				.isEqualTo(0);
	}

	/**
	 * Minimal consumer referencing Shared (sget of its constant) with a unique name.
	 */
	private static File consumerFile(String name) {
		return new SmaliSource(name).toFile();
	}

	private static final class SmaliSource {
		private final String name;

		SmaliSource(String name) {
			this.name = name;
		}

		File toFile() {
			try {
				File dir = java.nio.file.Files.createTempDirectory("cand2-consumer").toFile();
				File file = new File(dir, name + ".smali");
				java.nio.file.Files.writeString(file.toPath(), String.format(
						".class public Ldst/%1$s;\n"
								+ ".super Ljava/lang/Object;\n"
								+ "\n"
								+ "# direct methods\n"
								+ ".method public constructor <init>()V\n"
								+ "    .registers 1\n"
								+ "\n"
								+ "    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n"
								+ "\n"
								+ "    return-void\n"
								+ ".end method\n"
								+ "\n"
								+ ".method public readTag()Ljava/lang/String;\n"
								+ "    .registers 2\n"
								+ "\n"
								+ "    sget-object v0, Ldst/Shared;->TAG:Ljava/lang/String;\n"
								+ "\n"
								+ "    return-object v0\n"
								+ ".end method\n",
						name));
				file.deleteOnExit();
				dir.deleteOnExit();
				return file;
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}
}
