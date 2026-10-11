package jadx.core.dex.nodes;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.TreeSet;

import jadx.core.utils.ListUtils;

/**
 * Updates run under the root usage lock; readers retain immutable list snapshots.
 * Elements of the supplied exact class must have stable equals/hashCode implementations.
 */
final class UsageInfoList<T> {

	private static final int INDEX_THRESHOLD = 256;

	private final Class<T> elementClass;
	private volatile List<T> values;
	private Vector<T> vector;
	private PositionIndex<T> index;
	private boolean indexAllowed = true;

	private UsageInfoList(List<T> values, Class<T> elementClass) {
		this.values = values;
		this.elementClass = elementClass;
	}

	static <T> UsageInfoList<T> copyOf(List<T> values, Class<T> elementClass) {
		return new UsageInfoList<>(ListUtils.immutableCopy(values), elementClass);
	}

	List<T> list() {
		return values;
	}

	UsageInfoList<T> replace(T oldValue, T newValue) {
		try {
			if (supported(oldValue) && supported(newValue) && promote()) {
				Integer first = index.positions.get(oldValue);
				int position = first == null ? vector.size() : first;
				Vector<T> updated = vector.withValue(position, newValue);
				List<T> snapshot = Collections.unmodifiableList(updated);
				if (first != null) {
					index.removePosition(vector.get(position), position, updated);
				}
				index.addPosition(newValue, position);
				vector = updated;
				values = snapshot;
				return this;
			}
			discardIndex();
			List<T> updated = ListUtils.safeReplace(new ArrayList<>(values), oldValue, newValue);
			values = Collections.unmodifiableList(updated);
			indexAllowed &= supported(newValue);
			return this;
		} catch (RuntimeException | Error e) {
			discardIndex();
			throw e;
		}
	}

	UsageInfoList<T> add(T value) {
		try {
			if (vector != null && supported(value)) {
				Vector<T> updated = vector.withValue(vector.size(), value);
				List<T> snapshot = Collections.unmodifiableList(updated);
				index.addPosition(value, vector.size());
				vector = updated;
				values = snapshot;
			} else {
				discardIndex();
				List<T> updated = new ArrayList<>(values);
				updated.add(value);
				values = Collections.unmodifiableList(updated);
				indexAllowed &= supported(value);
			}
			return this;
		} catch (RuntimeException | Error e) {
			discardIndex();
			throw e;
		}
	}

	UsageInfoList<T> remove(T value) {
		List<T> updated = new ArrayList<>(values);
		updated.remove(value);
		return new UsageInfoList<>(Collections.unmodifiableList(updated), elementClass);
	}

	private boolean supported(T value) {
		return value == null || value.getClass() == elementClass;
	}

	private boolean promote() {
		if (vector != null) {
			return true;
		}
		if (!indexAllowed || values.size() < INDEX_THRESHOLD) {
			return false;
		}
		PositionIndex<T> newIndex = new PositionIndex<>();
		int position = 0;
		for (T value : values) {
			if (!supported(value)) {
				indexAllowed = false;
				return false;
			}
			newIndex.addPosition(value, position++);
		}
		Vector<T> newVector = Vector.copyOf(values);
		index = newIndex;
		vector = newVector;
		return true;
	}

	private void discardIndex() {
		vector = null;
		index = null;
	}

	private static final class PositionIndex<T> {
		private final Map<T, Integer> positions = new HashMap<>();
		private final Map<T, NavigableSet<Integer>> duplicates = new HashMap<>();

		private void addPosition(T value, int position) {
			Integer first = positions.putIfAbsent(value, position);
			if (first != null) {
				NavigableSet<Integer> indexes = duplicates.computeIfAbsent(value, key -> {
					NavigableSet<Integer> set = new TreeSet<>();
					set.add(first);
					return set;
				});
				indexes.add(position);
				if (position < first) {
					positions.put(value, position);
				}
			}
		}

		private void removePosition(T value, int position, Vector<T> updated) {
			positions.remove(value);
			NavigableSet<Integer> indexes = duplicates.remove(value);
			if (indexes != null) {
				indexes.remove(position);
				int first = indexes.first();
				// Keep an actual remaining node as the key, so removed nodes are not retained by the index.
				T remaining = updated.get(first);
				positions.put(remaining, first);
				if (indexes.size() > 1) {
					duplicates.put(remaining, indexes);
				}
			}
		}
	}

	private static final class Vector<T> extends AbstractList<T> implements RandomAccess {
		private static final int BITS = 5;
		private static final int WIDTH = 1 << BITS;
		private static final int MASK = WIDTH - 1;

		private final Object[] root;
		private final int shift;
		private final int size;

		private Vector(Object[] root, int shift, int size) {
			this.root = root;
			this.shift = shift;
			this.size = size;
		}

		private static <T> Vector<T> copyOf(List<T> values) {
			int shift = 0;
			while (values.size() > (1L << (shift + BITS))) {
				shift += BITS;
			}
			return new Vector<>(build(values.iterator(), shift, values.size()), shift, values.size());
		}

		private static Object[] build(Iterator<?> values, int shift, int count) {
			Object[] node = new Object[WIDTH];
			if (shift == 0) {
				for (int i = 0; i < count; i++) {
					node[i] = values.next();
				}
			} else {
				for (int i = 0; count > 0; i++) {
					int part = Math.min(count, 1 << shift);
					node[i] = build(values, shift - BITS, part);
					count -= part;
				}
			}
			return node;
		}

		private Vector<T> withValue(int position, T value) {
			int newSize = size;
			Object[] newRoot = root;
			int newShift = shift;
			if (position == size) {
				if (size == Integer.MAX_VALUE) {
					throw new OutOfMemoryError("Required list size too large");
				}
				newSize++;
				if (size == (1L << (shift + BITS))) {
					newRoot = new Object[WIDTH];
					newRoot[0] = root;
					newShift += BITS;
				}
			}
			return new Vector<>(put(newRoot, newShift, position, value), newShift, newSize);
		}

		private static Object[] put(Object[] node, int shift, int position, Object value) {
			Object[] updated = node == null ? new Object[WIDTH] : node.clone();
			int slot = (position >>> shift) & MASK;
			updated[slot] = shift == 0 ? value : put((Object[]) updated[slot], shift - BITS, position, value);
			return updated;
		}

		@Override
		public int size() {
			return size;
		}

		@Override
		@SuppressWarnings("unchecked")
		public T get(int position) {
			Objects.checkIndex(position, size);
			Object[] node = root;
			for (int level = shift; level > 0; level -= BITS) {
				node = (Object[]) node[(position >>> level) & MASK];
			}
			return (T) node[position & MASK];
		}

		@Override
		public Iterator<T> iterator() {
			return listIterator(0);
		}

		@Override
		public ListIterator<T> listIterator(int start) {
			if (start < 0 || start > size) {
				throw new IndexOutOfBoundsException("Index: " + start + ", size: " + size);
			}
			return new ListIterator<>() {
				private final Object[][] path = new Object[shift / BITS + 1][];
				private int position = start;
				private int leaf = -1;

				@SuppressWarnings("unchecked")
				private T valueAt(int element) {
					int nextLeaf = element >>> BITS;
					if (nextLeaf != leaf) {
						int level = shift / BITS;
						if (leaf < 0) {
							path[level] = root;
						} else {
							// Reuse shared branch prefixes, visiting each node once during a sequential walk.
							int changed = (leaf ^ nextLeaf) >>> BITS;
							level = 1;
							while (changed != 0) {
								level++;
								changed >>>= BITS;
							}
						}
						for (; level > 0; level--) {
							path[level - 1] = (Object[]) path[level][(element >>> (level * BITS)) & MASK];
						}
						leaf = nextLeaf;
					}
					return (T) path[0][element & MASK];
				}

				@Override
				public boolean hasNext() {
					return position < size;
				}

				@Override
				public T next() {
					if (!hasNext()) {
						throw new NoSuchElementException();
					}
					return valueAt(position++);
				}

				@Override
				public boolean hasPrevious() {
					return position > 0;
				}

				@Override
				public T previous() {
					if (!hasPrevious()) {
						throw new NoSuchElementException();
					}
					return valueAt(--position);
				}

				@Override
				public int nextIndex() {
					return position;
				}

				@Override
				public int previousIndex() {
					return position - 1;
				}

				@Override
				public void remove() {
					throw new UnsupportedOperationException();
				}

				@Override
				public void set(T value) {
					throw new UnsupportedOperationException();
				}

				@Override
				public void add(T value) {
					throw new UnsupportedOperationException();
				}
			};
		}

		@Override
		public Spliterator<T> spliterator() {
			return Spliterators.spliterator(iterator(), size, Spliterator.ORDERED);
		}
	}
}
