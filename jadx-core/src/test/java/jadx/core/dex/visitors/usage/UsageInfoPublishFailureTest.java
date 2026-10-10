package jadx.core.dex.visitors.usage;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import jadx.core.dex.nodes.RootNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the chunk failure-prefix semantics of parallel publish:
 * a mid-chunk preparation failure must publish the successfully prepared prefix
 * in entry order and throw at the failing entry, exactly as the serial publish does.
 */
class UsageInfoPublishFailureTest {

	/**
	 * Key with a controllable hash to fix the map entry iteration order.
	 */
	private static final class OrderedKey {
		private final int id;

		OrderedKey(int id) {
			this.id = id;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			return o instanceof OrderedKey && id == ((OrderedKey) o).id;
		}

		@Override
		public int hashCode() {
			// constant hash: small bins keep insertion order, so entries iterate in id order
			return 0;
		}

		@Override
		public String toString() {
			return "k" + id;
		}
	}

	@Test
	void midChunkFailurePublishesSerialPrefix() throws Exception {
		// single chunk of three entries, the middle entry (in map order) fails
		UseSet<OrderedKey, Integer> useSet = new UseSet<>();
		useSet.add(new OrderedKey(0), 10);
		useSet.add(new OrderedKey(1), 20);
		useSet.add(new OrderedKey(2), 30);
		List<OrderedKey> order = iterationOrder(useSet);
		assertThat(order).hasSize(3);
		OrderedKey failingKey = order.get(1);

		// serial oracle: publish entries in order until the failing entry
		List<Object> serialPublished = new ArrayList<>();
		RuntimeException serialFailure = runSerial(useSet, failingKey, serialPublished);
		assertThat(serialFailure).isInstanceOf(IllegalStateException.class);
		assertThat(serialPublished).as("mid-chunk failure keeps exactly one prefix entry").hasSize(1);

		// parallel publish must match the serial prefix behavior
		List<Object> parallelPublished = new ArrayList<>();
		RuntimeException parallelFailure = runParallel(useSet, failingKey, parallelPublished);
		assertThat(parallelFailure).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("failed");
		assertThat(parallelPublished)
				.as("parallel publish must keep the serial failure prefix")
				.containsExactlyElementsOf(serialPublished);
	}

	@Test
	void laterChunkFailurePublishesFullEarlierChunks() throws Exception {
		// 300 entries -> two chunks (256 + 44), the failing entry is in the second chunk
		UseSet<OrderedKey, Integer> useSet = new UseSet<>();
		List<OrderedKey> keys = new ArrayList<>(300);
		for (int i = 0; i < 300; i++) {
			OrderedKey key = new OrderedKey(i);
			keys.add(key);
			useSet.add(key, 100 + i);
		}
		OrderedKey failingKey = iterationOrder(useSet).get(260);

		List<Object> serialPublished = new ArrayList<>();
		RuntimeException serialFailure = runSerial(useSet, failingKey, serialPublished);
		assertThat(serialFailure).isInstanceOf(IllegalStateException.class);
		assertThat(serialPublished).hasSize(260);

		List<Object> parallelPublished = new ArrayList<>();
		RuntimeException parallelFailure = runParallel(useSet, failingKey, parallelPublished);
		assertThat(parallelFailure).isInstanceOf(IllegalStateException.class);
		assertThat(parallelPublished)
				.as("parallel publish must keep the serial failure prefix across chunks")
				.containsExactlyElementsOf(serialPublished);
	}

	private static RuntimeException runSerial(UseSet<OrderedKey, Integer> useSet,
			OrderedKey failingKey, List<Object> published) {
		RuntimeException failure = null;
		for (Map.Entry<OrderedKey, Set<Integer>> entry : entries(useSet)) {
			if (failure != null) {
				break;
			}
			if (entry.getKey().equals(failingKey)) {
				failure = new IllegalStateException("entry " + entry.getKey() + " failed");
				continue;
			}
			published.add(entry.getKey() + "=" + sorted(entry.getValue()));
		}
		return failure;
	}

	private RuntimeException runParallel(UseSet<OrderedKey, Integer> useSet,
			OrderedKey failingKey, List<Object> published) {
		// the transform sees only prepared values, identify the failing entry by its value list
		List<Integer> failingValues = sorted(useSet.get(failingKey));
		UsageInfoParallelExecutor executor = new UsageInfoParallelExecutor(2,
				java.util.concurrent.Executors.newFixedThreadPool(2));
		try {
			UsageInfo usageInfo = new UsageInfo((RootNode) null); // publish path never touches the root
			Function<List<Integer>, List<Integer>> transform = values -> {
				if (values.equals(failingValues)) {
					throw new IllegalStateException("entry " + failingKey + " failed");
				}
				return values;
			};
			BiConsumer<OrderedKey, List<Integer>> setter = (key, value) -> published.add(key + "=" + value);
			invokePublishUseSet(usageInfo, executor, useSet, transform, setter);
			throw new AssertionError("publish must fail at the failing entry");
		} catch (ReflectiveOperationException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IllegalStateException) {
				return (IllegalStateException) cause;
			}
			throw new AssertionError(e);
		} finally {
			executor.close();
		}
	}

	@SuppressWarnings("unchecked")
	private static void invokePublishUseSet(UsageInfo usageInfo,
			UsageInfoParallelExecutor executor,
			UseSet<OrderedKey, Integer> useSet,
			Function<List<Integer>, List<Integer>> transform,
			BiConsumer<OrderedKey, List<Integer>> setter) throws ReflectiveOperationException {
		Method publish = UsageInfo.class.getDeclaredMethod("publishUseSet",
				UsageInfoParallelExecutor.class, UseSet.class, Function.class, BiConsumer.class);
		publish.setAccessible(true);
		publish.invoke(usageInfo, executor, useSet, transform, setter);
	}

	private static List<OrderedKey> iterationOrder(UseSet<OrderedKey, Integer> useSet) {
		List<OrderedKey> order = new ArrayList<>();
		for (Map.Entry<OrderedKey, Set<Integer>> entry : entries(useSet)) {
			order.add(entry.getKey());
		}
		return order;
	}

	@SuppressWarnings("unchecked")
	private static List<Map.Entry<OrderedKey, Set<Integer>>> entries(UseSet<OrderedKey, Integer> useSet) {
		List<Map.Entry<OrderedKey, Set<Integer>>> entries = new ArrayList<>();
		useSet.visit((key, values) -> entries.add(new HashMap.SimpleImmutableEntry<>(key, values)));
		return entries;
	}

	private static List<Integer> sorted(Set<Integer> values) {
		List<Integer> list = new ArrayList<>(values);
		Collections.sort(list);
		return list;
	}
}
