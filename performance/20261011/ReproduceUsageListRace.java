import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jadx.core.utils.ListUtils;

/** Stress the unchanged helper used by InlineMethods on one shared usage list. Not a full CLI reproduction. */
public class ReproduceUsageListRace {

	private static final int WORKERS = 8;
	private static final int INITIAL_SIZE = 82;
	private static final int ATTEMPTS = 10_000;

	public static void main(String[] args) throws Exception {
		Object missing = new Object();
		List<Object> seed = Collections.nCopies(INITIAL_SIZE, new Object());
		List<Object> serial = new ArrayList<>(seed);
		for (int i = 0; i < WORKERS; i++) {
			if (ListUtils.safeReplace(serial, missing, new Object()) != serial) {
				throw new AssertionError("Nonempty list must retain its identity");
			}
		}
		int expected = INITIAL_SIZE + WORKERS;
		if (serial.size() != expected || serial.contains(null)) {
			throw new AssertionError("Sequential control failed");
		}
		System.out.println("sequential size=" + serial.size() + ", expected=" + expected);
		ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
		try {
			for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
				// A constructed full-capacity list targets the append/growth race.
				List<Object> shared = new ArrayList<>(seed);
				CountDownLatch ready = new CountDownLatch(WORKERS);
				CountDownLatch start = new CountDownLatch(1);
				List<Future<RuntimeException>> tasks = new ArrayList<>();
				for (int worker = 0; worker < WORKERS; worker++) {
					tasks.add(executor.submit(() -> {
						ready.countDown();
						start.await();
						try {
							ListUtils.safeReplace(shared, missing, new Object());
							return null;
						} catch (RuntimeException e) {
							return e;
						}
					}));
				}
				ready.await();
				start.countDown();
				boolean appendBoundsError = false;
				for (Future<RuntimeException> task : tasks) {
					RuntimeException failure = task.get();
					if (failure != null) {
						failure.printStackTrace(System.out);
						if (failure instanceof ArrayIndexOutOfBoundsException) {
							for (StackTraceElement frame : failure.getStackTrace()) {
								appendBoundsError |= frame.getClassName().equals("java.util.ArrayList")
										&& frame.getMethodName().equals("add");
							}
						}
					}
				}
				System.out.println("attempt=" + attempt + ", concurrent size=" + shared.size() + ", expected=" + expected);
				if (appendBoundsError) {
					return;
				}
			}
			throw new AssertionError("No ArrayList.add bounds error observed in " + ATTEMPTS + " attempts; inconclusive");
		} finally {
			executor.shutdownNow();
		}
	}
}
