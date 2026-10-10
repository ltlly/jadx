package jadx.core.dex.nodes.deps;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxArgs;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.nodes.deps.DependencyProcessingProfile.Span;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DependencyProcessingProfileTest {

	@TempDir
	Path temp;

	private static ClassNode owner() {
		return ClassNode.addSyntheticClass(new RootNode(new JadxArgs()), "profile.Owner", 0);
	}

	private static String[] row(Path path, String key, String recovery) throws Exception {
		return Files.readAllLines(path).stream().skip(1)
				.map(line -> line.split("\t", -1))
				.filter(values -> values[0].equals(key) && values[1].equals(recovery))
				.findFirst().orElseThrow(() -> new AssertionError("Missing row: " + key));
	}

	@Test
	void nestedTimingSubtractsChildrenAndClassTotalsDoNotDoubleCount() throws Exception {
		AtomicLong wall = new AtomicLong();
		AtomicLong cpu = new AtomicLong();
		DependencyProcessingProfile profile = new DependencyProcessingProfile(wall::get, cpu::get);
		ClassNode cls = owner();
		Span process = profile.begin(cls, "PROCESS/LEGACY/false", false, true);
		wall.addAndGet(9);
		cpu.addAndGet(6);
		process.acquiredMonitor(true);
		try (Span pass = profile.beginStage(cls, "PASS/0/Test")) {
			wall.addAndGet(7);
			cpu.addAndGet(4);
		}
		wall.addAndGet(5);
		cpu.addAndGet(3);
		process.close();
		process.close();
		profile.writeReport(temp);

		String[] stage = row(temp.resolve("stages.tsv"), "PROCESS/LEGACY/false", "true");
		assertThat(stage[2]).isEqualTo("1");
		assertThat(stage[4]).isEqualTo("13");
		assertThat(stage[5]).isEqualTo("9");
		assertThat(stage[6]).isEqualTo("21");
		assertThat(stage[7]).isEqualTo("14");
		assertThat(stage[8]).isEqualTo("9");
		String[] totals = row(temp.resolve("classes.tsv"), "profile.Owner", "true");
		assertThat(totals[5]).isEqualTo("13");
		assertThat(totals[7]).isEqualTo("21");
		assertThat(Files.readAllLines(temp.resolve("status.tsv")).get(1).split("\t")[0]).isEqualTo("0");
	}

	@Test
	void exceptionsCloseSpansAndUnavailableCpuIsNotReportedAsKnown() throws Exception {
		AtomicLong wall = new AtomicLong();
		DependencyProcessingProfile profile = new DependencyProcessingProfile(wall::get, () -> {
			throw new UnsupportedOperationException("CPU clock");
		});
		ClassNode cls = owner();
		assertThatThrownBy(() -> {
			try (Span span = profile.begin(cls, "PROCESS/LEGACY/false", false, true)) {
				try (Span pass = profile.beginStage(cls, "PASS/0/Failure")) {
					wall.addAndGet(5);
					throw new IllegalStateException("pass failure");
				}
			}
		}).isInstanceOf(IllegalStateException.class);
		profile.writeReport(temp);
		String[] stage = row(temp.resolve("stages.tsv"), "PASS/0/Failure", "false");
		assertThat(stage[2]).isEqualTo("1");
		assertThat(stage[3]).isEqualTo("0");
		assertThat(stage[4]).isEqualTo("0");
		String[] status = Files.readAllLines(temp.resolve("status.tsv")).get(1).split("\t");
		assertThat(status[0]).isEqualTo("0");
		assertThat(status[1]).isEqualTo("0");
	}

	@Test
	void timelineParentsSkipUntracedPassSpans() throws Exception {
		DependencyProcessingProfile profile = new DependencyProcessingProfile(() -> 0L, () -> 0L);
		ClassNode cls = owner();
		try (Span parent = profile.begin(cls, "GENERATE_REQUEST", false, true)) {
			try (Span pass = profile.beginStage(cls, "PASS/0/Inline")) {
				try (Span child = profile.begin(cls, "PROCESS/LEGACY/false", true, true)) {
					// no work needed for the trace-parent check
				}
			}
		}
		profile.writeReport(temp);
		List<String> lines = Files.readAllLines(temp.resolve("timeline.tsv"));
		assertThat(lines).hasSize(3);
		String[] child = lines.get(1).split("\t");
		String[] parent = lines.get(2).split("\t");
		assertThat(child[1]).isEqualTo(parent[0]);
		assertThat(parent[1]).isEqualTo("0");
	}

	@Test
	void threadLocalNestingAndConcurrentCountersAreIndependent() throws Exception {
		DependencyProcessingProfile profile = new DependencyProcessingProfile(() -> 0L, () -> 0L);
		ClassNode cls = owner();
		ExecutorService executor = Executors.newFixedThreadPool(8);
		CyclicBarrier barrier = new CyclicBarrier(8);
		try {
			Future<?>[] tasks = new Future<?>[8];
			for (int i = 0; i < tasks.length; i++) {
				tasks[i] = executor.submit(() -> {
					try (Span span = profile.begin(cls, "PROCESS/LEGACY/false", false, true)) {
						barrier.await(10, TimeUnit.SECONDS);
						try (Span pass = profile.beginStage(cls, "PASS/0/Parallel")) {
							barrier.await(10, TimeUnit.SECONDS);
						}
					}
					return null;
				});
			}
			for (Future<?> task : tasks) {
				task.get(20, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
		profile.writeReport(temp);
		assertThat(row(temp.resolve("stages.tsv"), "PASS/0/Parallel", "false")[2]).isEqualTo("8");
		String[] status = Files.readAllLines(temp.resolve("status.tsv")).get(1).split("\t");
		assertThat(status[0]).isEqualTo("0");
		assertThat(status[1]).isEqualTo("0");
		assertThatThrownBy(() -> profile.writeReport(temp)).isInstanceOf(java.io.IOException.class);
	}
}
