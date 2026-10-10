package jadx.core.dex.visitors.usage;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JadxArgs;
import jadx.api.plugins.input.data.ICodeReader;
import jadx.core.utils.Utils;

/**
 * Short-lived executor owning worker threads for a single usage info collection/apply epoch.
 * Created per epoch, all worker threads are guaranteed to be stopped after {@link #close()}.
 */
final class UsageInfoParallelExecutor implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger(UsageInfoParallelExecutor.class);

	/**
	 * Kill switch: '-Djadx.usage.parallel=false' disables parallel usage info collection and publish.
	 * Unknown values are treated as disabled. Read on every epoch start, never cached as a constant.
	 */
	static final String PARALLEL_PROPERTY = "jadx.usage.parallel";

	private static final int MAX_WORKERS = 8;
	private static final long SHUTDOWN_TIMEOUT_MS = 30_000;

	/**
	 * Default conservative budget for a single worker-local shard graph (bytes, 8B reference width).
	 */
	static final long DEFAULT_SHARD_BUDGET = 4L * 1024 * 1024;

	private final int parallelism;
	private final @Nullable ExecutorService pool;
	// non-final to allow tests to force oversize fallbacks
	private long shardBudget = DEFAULT_SHARD_BUDGET;

	private UsageInfoParallelExecutor(int parallelism, @Nullable ExecutorService pool) {
		this.parallelism = parallelism;
		this.pool = pool;
	}

	static boolean isParallelEnabled() {
		String propValue = System.getProperty(PARALLEL_PROPERTY);
		if (propValue == null || propValue.equalsIgnoreCase("true")) {
			return true;
		}
		if (propValue.equalsIgnoreCase("false")) {
			return false;
		}
		LOG.warn("Unknown value '{}' for '{}' system property, parallel usage info processing disabled", propValue, PARALLEL_PROPERTY);
		return false;
	}

	static UsageInfoParallelExecutor open(JadxArgs args) {
		if (!isParallelEnabled()) {
			return new UsageInfoParallelExecutor(1, null);
		}
		int threads = Math.min(Math.min(MAX_WORKERS, Runtime.getRuntime().availableProcessors()), args.getThreadsCount());
		if (threads <= 1) {
			return new UsageInfoParallelExecutor(1, null);
		}
		return new UsageInfoParallelExecutor(threads,
				Executors.newFixedThreadPool(threads, Utils.simpleThreadFactory("usage-p")));
	}

	boolean isParallel() {
		return pool != null;
	}

	int getParallelism() {
		return parallelism;
	}

	long getShardBudget() {
		return shardBudget;
	}

	void setShardBudget(long shardBudget) {
		this.shardBudget = shardBudget;
	}

	<T> Future<T> submit(Callable<T> task) {
		ExecutorService executorService = this.pool;
		if (executorService == null) {
			throw new IllegalStateException("No pool for serial executor");
		}
		return executorService.submit(task);
	}

	/**
	 * Force running tasks to stop, used on fatal errors before {@link #close()}.
	 */
	void cancelRunningTasks() {
		ExecutorService executorService = this.pool;
		if (executorService != null) {
			executorService.shutdownNow();
		}
	}

	@Override
	public void close() {
		ExecutorService executorService = this.pool;
		if (executorService == null) {
			return;
		}
		executorService.shutdown();
		try {
			if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				LOG.warn("Usage info worker threads didn't stop in time, forcing shutdown");
				executorService.shutdownNow();
				if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
					LOG.error("Usage info worker threads refused to terminate");
				}
			}
		} catch (InterruptedException e) {
			executorService.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Reader-declared capability for class-exclusive concurrent scanning,
	 * built-in dex readers opt in, unknown/custom readers default to serial fallback.
	 */
	static boolean readerSupportsParallelScan(ICodeReader reader) {
		return reader.supportsParallelClassScan();
	}

	/**
	 * Internal control signal: worker-local graph budget exhausted,
	 * the shard is replayed serially on the caller thread. Extends {@link Error}
	 * so it can't be converted into a recoverable 'Dependency scan failed' error.
	 */
	static final class ScanBudgetExceeded extends Error {
		private static final long serialVersionUID = 0L;

		ScanBudgetExceeded() {
			super(null, null, false, false);
		}
	}

	/**
	 * Conservative byte budget for worker-local graph construction.
	 */
	static final class ScanBudget {
		private long remainingBytes;

		ScanBudget(long limitBytes) {
			this.remainingBytes = limitBytes;
		}

		void charge(long bytes) {
			remainingBytes -= bytes;
			if (remainingBytes < 0) {
				throw new ScanBudgetExceeded();
			}
		}
	}

	/**
	 * Prepared publish result for a single graph entry: sorted/resolved value list.
	 * Published serially by the caller thread in original entry order.
	 */
	static final class PreparedEntry<K, V> {
		private final K key;
		private final @Nullable Set<?> sourceValues;
		private @Nullable List<V> value;
		private boolean prepareSerially;

		PreparedEntry(K key, @Nullable Set<?> sourceValues) {
			this.key = key;
			this.sourceValues = sourceValues;
		}

		K getKey() {
			return key;
		}

		@Nullable
		Set<?> getSourceValues() {
			return sourceValues;
		}

		@Nullable
		List<V> getValue() {
			return value;
		}

		void setValue(@Nullable List<V> value) {
			this.value = value;
		}

		boolean isPrepareSerially() {
			return prepareSerially;
		}

		void setPrepareSerially(boolean prepareSerially) {
			this.prepareSerially = prepareSerially;
		}
	}
}
