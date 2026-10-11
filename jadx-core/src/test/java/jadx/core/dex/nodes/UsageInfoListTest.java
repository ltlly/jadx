package jadx.core.dex.nodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Spliterator;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import jadx.core.utils.ListUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageInfoListTest {

	@Test
	void detachesInputsAndKeepsSmallSnapshots() {
		Key first = new Key(1);
		Key equal = new Key(1);
		List<Key> input = new ArrayList<>(Arrays.asList(first, equal, null));
		UsageInfoList<Key> values = UsageInfoList.copyOf(input, Key.class);
		List<Key> before = values.list();
		input.clear();
		Key replacement = new Key(2);
		values.replace(new Key(1), replacement).replace(new Key(9), null);
		assertThat(values.list()).containsExactly(replacement, equal, null, null);
		assertThat(before).containsExactly(first, equal, null);
		values = values.remove(null);
		assertThat(values.list()).containsExactly(replacement, equal, null);
		assertThat(before.get(0)).isSameAs(first);
		UsageInfoList<Key> empty = UsageInfoList.copyOf(Collections.emptyList(), Key.class);
		empty.add(first);
		assertThat(UsageInfoList.copyOf(Collections.emptyList(), Key.class).list()).isEmpty();
	}

	@Test
	void replacesFirstDuplicatesWithEqualObjectsAndHashCollisions() {
		List<Key> expected = keys(512, 17);
		UsageInfoList<Key> values = UsageInfoList.copyOf(expected, Key.class);
		List<Key> before = values.list();
		List<Key> original = new ArrayList<>(expected);
		for (int i = 0; i < 80; i++) {
			Key oldValue = i % 5 == 0 ? null : new Key(i % 19);
			Key newValue = i % 7 == 0 ? null : new Key((i + 1) % 19);
			ListUtils.safeReplace(expected, oldValue, newValue);
			values.replace(oldValue, newValue);
			assertSameValues(values.list(), expected);
		}
		Key equalReplacement = new Key(4);
		int first = expected.indexOf(equalReplacement);
		values.replace(equalReplacement, equalReplacement);
		ListUtils.safeReplace(expected, equalReplacement, equalReplacement);
		assertThat(values.list().get(first < 0 ? expected.size() - 1 : first)).isSameAs(equalReplacement);
		assertSameValues(values.list(), expected);
		assertSameValues(before, original);
	}

	@Test
	void appendAndReplaceAcrossTreeBoundaries() {
		int[] sizes = { 31, 32, 33, 255, 256, 257, 1023, 1024, 1025, 32767, 32768, 32769 };
		for (int size : sizes) {
			List<Integer> expected = new ArrayList<>();
			for (int i = 0; i < size; i++) {
				expected.add(i);
			}
			UsageInfoList<Integer> values = UsageInfoList.copyOf(expected, Integer.class);
			List<Integer> before = values.list();
			values.replace(-1, size);
			expected.add(size);
			values.replace(size, -2).add(null).replace(0, -3);
			expected.set(size, -2);
			expected.add(null);
			expected.set(0, -3);
			assertThat(values.list()).as("size %s", size).containsExactlyElementsOf(expected);
			assertThat(values.list().toArray()).containsExactlyElementsOf(expected);
			ListIterator<Integer> reverse = values.list().listIterator(values.list().size());
			for (int i = expected.size() - 1; i >= 0; i--) {
				assertThat(reverse.previous()).isEqualTo(expected.get(i));
			}
			assertThat(reverse.hasPrevious()).isFalse();
			assertThat(before).hasSize(size);
			assertThat(before.get(0)).isZero();
			assertThat(before.get(size - 1)).isEqualTo(size - 1);
		}
	}

	@Test
	void indexMovesBetweenSingleAndDuplicatePositions() {
		List<Key> expected = keys(300, 300);
		UsageInfoList<Key> values = UsageInfoList.copyOf(expected, Key.class);
		Key[] oldValues = { new Key(0), new Key(1), new Key(1), new Key(700), new Key(701), null, null };
		Key[] newValues = { new Key(1), new Key(2), new Key(3), null, null, new Key(4), new Key(5) };
		for (int i = 0; i < oldValues.length; i++) {
			values.replace(oldValues[i], newValues[i]);
			ListUtils.safeReplace(expected, oldValues[i], newValues[i]);
			assertSameValues(values.list(), expected);
		}
	}

	@Test
	void randomOperationsMatchArrayListAndRetainSnapshots() {
		Random random = new Random(739321);
		List<Key> expected = keys(240, 53);
		UsageInfoList<Key> values = UsageInfoList.copyOf(expected, Key.class);
		List<List<Key>> snapshots = new ArrayList<>();
		List<List<Key>> snapshotCopies = new ArrayList<>();
		for (int i = 0; i < 2000; i++) {
			Key oldValue = random.nextInt(9) == 0 ? null : new Key(random.nextInt(71));
			Key newValue = random.nextInt(9) == 0 ? null : new Key(random.nextInt(71));
			int operation = random.nextInt(10);
			if (operation < 6) {
				ListUtils.safeReplace(expected, oldValue, newValue);
				values.replace(oldValue, newValue);
			} else if (operation < 9) {
				expected.add(newValue);
				values.add(newValue);
			} else {
				expected.remove(oldValue);
				values = values.remove(oldValue);
			}
			assertSameValues(values.list(), expected);
			if (i % 100 == 0) {
				snapshots.add(values.list());
				snapshotCopies.add(new ArrayList<>(expected));
			}
		}
		for (int i = 0; i < snapshots.size(); i++) {
			assertSameValues(snapshots.get(i), snapshotCopies.get(i));
		}
	}

	@Test
	void keepsListReadApiAndRejectsMutations() {
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 1100; i++) {
			expected.add("value" + i);
		}
		expected.set(14, null);
		UsageInfoList<String> values = UsageInfoList.copyOf(expected, String.class);
		values.replace("absent", null);
		expected.add(null);
		List<String> snapshot = values.list();
		assertThat(snapshot).isEqualTo(expected);
		assertThat(expected).isEqualTo(snapshot);
		assertThat(snapshot.hashCode()).isEqualTo(expected.hashCode());
		assertThat(snapshot.toString()).isEqualTo(expected.toString());
		assertThat(snapshot.indexOf(null)).isEqualTo(14);
		assertThat(snapshot.lastIndexOf(null)).isEqualTo(1100);
		assertThat(snapshot.containsAll(expected)).isTrue();
		assertThat(snapshot.stream().collect(Collectors.toList())).isEqualTo(expected);
		assertThat(snapshot.toArray(new String[0])).containsExactlyElementsOf(expected);
		String[] oversized = new String[1200];
		Arrays.fill(oversized, "sentinel");
		assertThat(snapshot.toArray(oversized)).isSameAs(oversized);
		assertThat(oversized[1101]).isNull();
		assertThat(oversized[1102]).isEqualTo("sentinel");
		assertThatThrownBy(() -> snapshot.toArray(new Integer[0])).isInstanceOf(ArrayStoreException.class);
		assertThatThrownBy(() -> snapshot.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
		assertThatThrownBy(() -> snapshot.get(snapshot.size())).isInstanceOf(IndexOutOfBoundsException.class);
		assertThatThrownBy(() -> snapshot.listIterator(-1)).isInstanceOf(IndexOutOfBoundsException.class);
		assertThatThrownBy(() -> snapshot.listIterator(snapshot.size() + 1)).isInstanceOf(IndexOutOfBoundsException.class);
		assertThatThrownBy(() -> snapshot.listIterator(0).previous()).isInstanceOf(NoSuchElementException.class);
		assertThatThrownBy(() -> snapshot.listIterator(snapshot.size()).next()).isInstanceOf(NoSuchElementException.class);

		List<String> subList = snapshot.subList(31, 1025);
		List<String> expectedSubList = new ArrayList<>(expected.subList(31, 1025));
		ListIterator<String> cursor = snapshot.listIterator(32);
		assertThat(cursor.nextIndex()).isEqualTo(32);
		assertThat(cursor.previousIndex()).isEqualTo(31);
		assertThat(cursor.previous()).isEqualTo(expected.get(31));
		assertThat(cursor.next()).isEqualTo(expected.get(31));
		assertThat(cursor.next()).isEqualTo(expected.get(32));
		Iterator<String> iterator = snapshot.iterator();
		iterator.next();
		assertThatThrownBy(iterator::remove).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> cursor.set("changed")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> cursor.add("changed")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> snapshot.add("changed")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> snapshot.set(0, "changed")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> snapshot.remove(0)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> snapshot.removeIf(value -> false)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> snapshot.replaceAll(value -> value)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> snapshot.sort(null)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(subList::clear).isInstanceOf(UnsupportedOperationException.class);

		Spliterator<String> remaining = snapshot.spliterator();
		Spliterator<String> prefix = remaining.trySplit();
		List<String> splitValues = new ArrayList<>();
		if (prefix != null) {
			prefix.forEachRemaining(splitValues::add);
		}
		remaining.forEachRemaining(splitValues::add);
		assertThat(splitValues).isEqualTo(expected);
		values.replace("value31", "changed").add("last");
		assertThat(snapshot).isEqualTo(expected);
		assertThat(subList).isEqualTo(expectedSubList);
	}

	@Test
	void unknownSubclassesKeepLinearEqualityAndNeverHash() {
		List<Key> expected = keys(300, 300);
		UsageInfoList<Key> values = UsageInfoList.copyOf(expected, Key.class);
		values.replace(new Key(0), new Key(300));
		ListUtils.safeReplace(expected, new Key(0), values.list().get(0));
		Key unknown = new UnhashedKey(1);
		Key replacement = new Key(301);
		values.replace(unknown, replacement);
		ListUtils.safeReplace(expected, unknown, replacement);
		values.add(unknown).replace(new Key(2), unknown);
		expected.add(unknown);
		ListUtils.safeReplace(expected, new Key(2), unknown);
		assertSameValues(values.list(), expected);
		values = UsageInfoList.copyOf(expected, Key.class);
		values.replace(new Key(3), replacement).replace(new Key(4), replacement);
		ListUtils.safeReplace(expected, new Key(3), replacement);
		ListUtils.safeReplace(expected, new Key(4), replacement);
		assertSameValues(values.list(), expected);
	}

	@Test
	void failedIndexUpdateLeavesSnapshotAndAllowsRebuild() {
		List<FaultKey> input = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			input.add(new FaultKey(i));
		}
		UsageInfoList<FaultKey> values = UsageInfoList.copyOf(input, FaultKey.class);
		values.replace(new FaultKey(299), new FaultKey(299));
		List<FaultKey> before = values.list();
		FaultKey replacement = new FaultKey(400);
		replacement.fail = true;
		assertThatThrownBy(() -> values.replace(input.get(0), replacement)).isInstanceOf(IllegalStateException.class);
		assertThat(values.list()).isSameAs(before);
		replacement.fail = false;
		values.replace(input.get(0), replacement);
		assertThat(values.list().get(0)).isSameAs(replacement);
		assertThat(before.get(0)).isSameAs(input.get(0));
		assertThat(values.list()).hasSize(300);
	}

	private static List<Key> keys(int count, int distinct) {
		List<Key> values = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			values.add(new Key(i % distinct));
		}
		return values;
	}

	private static <T> void assertSameValues(List<T> actual, List<T> expected) {
		assertThat(actual).hasSize(expected.size());
		for (int i = 0; i < expected.size(); i++) {
			assertThat(actual.get(i)).as("element %s", i).isSameAs(expected.get(i));
		}
	}

	private static class Key {
		private final int id;

		private Key(int id) {
			this.id = id;
		}

		@Override
		public int hashCode() {
			return id % 7;
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof Key && id == ((Key) object).id;
		}
	}

	@SuppressWarnings("checkstyle:EqualsHashCode") // Inherit equality while making unexpected hashing fail.
	private static final class UnhashedKey extends Key {
		private UnhashedKey(int id) {
			super(id);
		}

		@Override
		public int hashCode() {
			throw new AssertionError("Unknown subclasses must use linear lookup");
		}
	}

	@SuppressWarnings("checkstyle:EqualsHashCode") // Inherit equality while injecting a hashing failure.
	private static final class FaultKey extends Key {
		private boolean fail;

		private FaultKey(int id) {
			super(id);
		}

		@Override
		public int hashCode() {
			if (fail) {
				throw new IllegalStateException("index update failed");
			}
			return super.hashCode();
		}
	}
}
