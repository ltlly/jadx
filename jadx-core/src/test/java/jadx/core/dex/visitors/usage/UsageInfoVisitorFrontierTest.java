package jadx.core.dex.visitors.usage;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
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
 * Regression tests for the oversized-shard frontier in parallel collection:
 * an oversized shard scans the global graph directly, so it must run only after
 * all earlier shards are merged, and an empty in-flight queue is never polled.
 */
class UsageInfoVisitorFrontierTest extends SmaliTest {

	private static final String SMALI_DIR = "src/test/smali/others/TestUsageInfoFrontier";

	/**
	 * Budget: Aa and Cc stay normal shards, the big middle class Bb becomes a single oversize shard.
	 */
	private static final long MIXED_BUDGET = 1500;

	private final List<File> smaliFiles = loadSmaliFiles();

	@BeforeEach
	@Override
	public void init() {
		super.init();
		getArgs().setThreadsCount(8);
	}

	@AfterEach
	public void resetParallelFlag() {
		System.clearProperty(UsageInfoParallelExecutor.PARALLEL_PROPERTY);
	}

	private static List<File> loadSmaliFiles() {
		File dir = new File(SMALI_DIR);
		File[] files = dir.listFiles((d, name) -> name.endsWith(".smali"));
		assertThat(files).as("Smali fixture not found in " + dir).isNotNull();
		return Arrays.stream(files).sorted().collect(Collectors.toList());
	}

	private RootNode loadRoot() {
		System.clearProperty(UsageInfoParallelExecutor.PARALLEL_PROPERTY);
		jadxDecompiler = loadFiles(smaliFiles);
		return jadxDecompiler.getRoot();
	}

	/**
	 * Shards [normal(Aa), oversize(Bb), normal(Cc)]: while the first shard task is not run,
	 * the oversize shard must not be scanned into the global graph.
	 * The Apache legacy flag is a global side effect of the oversize serial scan
	 * (worker-local graphs defer it to a merge), used here as the scan marker.
	 */
	@Test
	void oversizeShardWaitsForEarlierShardMerge() throws Exception {
		RootNode root = loadRoot();
		assertThat(classNames(root)).containsExactly("ufr.Aa", "ufr.Bb", "ufr.Cc");

		IUsageInfoData serialData = UsageInfoVisitor.buildUsageDataSerial(root);
		Map<String, List<String>> serialSnapshot = snapshot(serialData);

		ManualShardPool pool = new ManualShardPool();
		UsageInfoParallelExecutor executor = new UsageInfoParallelExecutor(2, pool);
		try {
			executor.setShardBudget(MIXED_BUDGET); // shards: normal(Aa), oversize(Bb), normal(Cc)
			// reset the global flag: it becomes the marker of the oversize scan
			root.getGradleInfoStorage().setUseApacheHttpLegacy(false);

			AtomicReference<Throwable> failure = new AtomicReference<>();
			AtomicReference<IUsageInfoData> builtData = new AtomicReference<>();
			CountDownLatch done = new CountDownLatch(1);
			Thread buildThread = new Thread(() -> {
				try {
					builtData.set(UsageInfoVisitor.buildUsageDataParallel(root, executor));
				} catch (Throwable e) {
					failure.set(e);
				} finally {
					done.countDown();
				}
			});
			buildThread.start();

			// shard 0 task submitted but held back by the manual pool
			awaitQueuedTasks(pool, 1);
			Thread.sleep(200);
			// the oversize shard must not be scanned while an earlier shard is in flight
			assertThat(root.getGradleInfoStorage().isUseApacheHttpLegacy())
					.as("oversize shard scanned before earlier shard merge")
					.isFalse();
			assertThat(pool.queuedCount()).as("only the first shard task may be submitted").isEqualTo(1);

			// release shard 0: merge completes, then the oversize shard is scanned
			pool.runNext();
			awaitApacheFlag(root, true);
			assertThat(root.getGradleInfoStorage().isUseApacheHttpLegacy()).isTrue();

			// release shard 2 and finish
			awaitQueuedTasks(pool, 1);
			pool.runNext();
			assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(failure.get()).as("parallel collection failed").isNull();

			assertThat(snapshot(builtData.get())).isEqualTo(serialSnapshot);
			assertThat(pool.totalSubmitted()).isEqualTo(2);
		} finally {
			executor.close();
		}
	}

	/**
	 * All shards oversized: everything is scanned inline on the caller thread,
	 * the in-flight queue stays empty and must never be polled (pre-fix NPE).
	 */
	@Test
	void allOversizeShardsNeverPollEmptyQueue() {
		RootNode root = loadRoot();
		assertThat(classNames(root)).containsExactly("ufr.Aa", "ufr.Bb", "ufr.Cc");

		IUsageInfoData serialData = UsageInfoVisitor.buildUsageDataSerial(root);

		ManualShardPool pool = new ManualShardPool();
		UsageInfoParallelExecutor executor = new UsageInfoParallelExecutor(2, pool);
		try {
			executor.setShardBudget(1); // every class is oversized
			IUsageInfoData parallelData = UsageInfoVisitor.buildUsageDataParallel(root, executor);
			assertThat(snapshot(parallelData)).isEqualTo(snapshot(serialData));
			assertThat(pool.totalSubmitted()).as("no worker task expected for all-oversize shards").isEqualTo(0);
		} finally {
			executor.close();
		}
	}

	private static List<String> classNames(RootNode root) {
		return root.getClasses(false).stream().map(ClassNode::getRawName).collect(Collectors.toList());
	}

	private static void awaitQueuedTasks(ManualShardPool pool, int count) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (pool.queuedCount() < count) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("Timed out waiting for " + count + " queued shard tasks");
			}
			Thread.sleep(5);
		}
	}

	private static void awaitApacheFlag(RootNode root, boolean expected) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (root.getGradleInfoStorage().isUseApacheHttpLegacy() != expected) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("Timed out waiting for apache flag to become " + expected);
			}
			Thread.sleep(5);
		}
	}

	/**
	 * Test worker pool: tasks are queued and executed only when the test runs them,
	 * emulating a shard worker that never finishes.
	 */
	private static final class ManualShardPool extends AbstractExecutorService {
		private final Deque<FutureTask<?>> queue = new ArrayDeque<>();
		private int totalSubmitted;
		private boolean shutdown;

		@Override
		public void execute(Runnable command) {
			queue.add((FutureTask<?>) command);
			totalSubmitted++;
		}

		void runNext() {
			FutureTask<?> task = queue.pollFirst();
			assertThat(task).as("no queued task to run").isNotNull();
			task.run();
		}

		int queuedCount() {
			return queue.size();
		}

		int totalSubmitted() {
			return totalSubmitted;
		}

		@Override
		public void shutdown() {
			shutdown = true;
		}

		@Override
		public List<Runnable> shutdownNow() {
			shutdown = true;
			return Collections.emptyList();
		}

		@Override
		public boolean isShutdown() {
			return shutdown;
		}

		@Override
		public boolean isTerminated() {
			return shutdown;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return true;
		}
	}

	/**
	 * Snapshot the full usage graph into an ordered map of raw id lists.
	 */
	private static Map<String, List<String>> snapshot(IUsageInfoData data) {
		Map<String, List<String>> snapshot = new TreeMap<>();
		data.visitUsageData(new IUsageInfoVisitor() {
			@Override
			public void visitClassDeps(ClassNode cls, List<ClassNode> deps) {
				snapshot.put("deps:" + cls.getRawName(), classIds(deps));
			}

			@Override
			public void visitClassUsage(ClassNode cls, List<ClassNode> usage) {
				snapshot.put("use:" + cls.getRawName(), classIds(usage));
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

	private static List<String> classIds(List<ClassNode> classes) {
		return classes.stream().map(ClassNode::getRawName).collect(Collectors.toList());
	}

	private static List<String> mthIds(List<MethodNode> methods) {
		return methods.stream().map(mth -> mth.getMethodInfo().getRawFullId()).collect(Collectors.toList());
	}

	private static List<String> unresolvedIds(List<MethodInfo> methods) {
		return methods.stream().map(MethodInfo::getRawFullId).collect(Collectors.toList());
	}
}
