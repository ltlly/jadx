package jadx.core.dex.visitors.usage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.Nullable;

public class UseSet<K, V> {
	/**
	 * Conservative allocation costs for budget accounting (reference width assumed 8B)
	 */
	private static final int NEW_SET_COST = 256;
	private static final int ADD_COST = 48;

	private final Map<K, Set<V>> useMap = new HashMap<>();
	@Nullable
	private final UsageInfoParallelExecutor.ScanBudget scanBudget;

	public UseSet() {
		this(null);
	}

	UseSet(@Nullable UsageInfoParallelExecutor.ScanBudget scanBudget) {
		this.scanBudget = scanBudget;
	}

	public void add(K obj, V use) {
		if (obj == use) {
			// self excluded
			return;
		}
		Set<V> set = useMap.get(obj);
		if (scanBudget != null) {
			scanBudget.charge(set == null ? NEW_SET_COST : ADD_COST);
		}
		if (set == null) {
			set = useMap.computeIfAbsent(obj, k -> new HashSet<>());
		}
		set.add(use);
	}

	public Set<V> get(K obj) {
		return useMap.get(obj);
	}

	public Set<V> getOrDefault(K obj, Set<V> defaultValue) {
		return useMap.getOrDefault(obj, defaultValue);
	}

	public void visit(BiConsumer<K, Set<V>> consumer) {
		for (Map.Entry<K, Set<V>> entry : useMap.entrySet()) {
			consumer.accept(entry.getKey(), entry.getValue());
		}
	}

	/**
	 * Union entries from a completed worker-local set into this set.
	 * A local value set is transferred (stolen) if its key is absent, otherwise added into the existing
	 * set.
	 * The source set is cleared, it should not be used afterwards.
	 */
	public void mergeFrom(UseSet<K, V> other) {
		for (Map.Entry<K, Set<V>> entry : other.useMap.entrySet()) {
			Set<V> prev = useMap.putIfAbsent(entry.getKey(), entry.getValue());
			if (prev != null) {
				prev.addAll(entry.getValue());
			}
		}
		other.useMap.clear();
	}

	/**
	 * Iterator over map entries for bounded batch enumeration during publish,
	 * the underlying map must not be modified while iterating.
	 */
	Iterator<Map.Entry<K, Set<V>>> entryIterator() {
		return useMap.entrySet().iterator();
	}
}
