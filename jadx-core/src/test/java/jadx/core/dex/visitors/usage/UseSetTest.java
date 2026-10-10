package jadx.core.dex.visitors.usage;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UseSetTest {

	/**
	 * Key with a controllable hash code to test collision handling.
	 */
	private static final class CollisionKey {
		private final int id;
		private final int hash;

		CollisionKey(int id, int hash) {
			this.id = id;
			this.hash = hash;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof CollisionKey)) {
				return false;
			}
			return id == ((CollisionKey) o).id;
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	@Test
	void addExcludesSelfByIdentity() {
		UseSet<String, String> useSet = new UseSet<>();
		String key = "key";
		useSet.add(key, key);
		assertThat(useSet.get(key)).isNull();
	}

	@Test
	void addDeduplicatesEqualValues() {
		UseSet<String, String> useSet = new UseSet<>();
		useSet.add("a", "v");
		useSet.add("a", "v");
		Set<String> values = useSet.get("a");
		assertThat(values).containsExactly("v");
	}

	@Test
	void mergeUnionsRatherThanOverwrites() {
		// three local graphs referencing the same callee with colliding hash keys
		CollisionKey callee = new CollisionKey(1, 42);
		CollisionKey callerA = new CollisionKey(2, 42);
		CollisionKey callerB = new CollisionKey(3, 42);

		UseSet<CollisionKey, CollisionKey> global = new UseSet<>();
		UseSet<CollisionKey, CollisionKey> local1 = new UseSet<>();
		UseSet<CollisionKey, CollisionKey> local2 = new UseSet<>();
		UseSet<CollisionKey, CollisionKey> local3 = new UseSet<>();

		local1.add(callee, callerA);
		local2.add(callee, callerB);
		local3.add(callee, callerA); // duplicate of local1 contribution
		local3.add(callee, callee); // self excluded

		global.mergeFrom(local1);
		global.mergeFrom(local2);
		global.mergeFrom(local3);

		assertThat(global.get(callee)).containsExactlyInAnyOrder(callerA, callerB);
		// identity self exclusion preserved across merge
		assertThat(global.get(callee)).doesNotContain(callee);
		// transferred local maps are released
		assertThat(local1.get(callee)).isNull();
		assertThat(local2.get(callee)).isNull();
		assertThat(local3.get(callee)).isNull();
	}

	@Test
	void mergeTransfersLocalSetWhenKeyAbsent() {
		UseSet<String, String> global = new UseSet<>();
		UseSet<String, String> local = new UseSet<>();
		local.add("k", "v1");
		local.add("k", "v2");

		global.mergeFrom(local);

		// transferred set keeps content and is not re-copied
		assertThat(global.get("k")).containsExactlyInAnyOrder("v1", "v2");
		// merged (transferred) local set must not be used afterwards
		local.add("k", "v3");
		assertThat(global.get("k")).containsExactlyInAnyOrder("v1", "v2");
	}

	@Test
	void mergeAddsIntoExistingSetWhenKeyPresent() {
		UseSet<String, String> global = new UseSet<>();
		global.add("k", "old");

		UseSet<String, String> local = new UseSet<>();
		local.add("k", "old"); // duplicate
		local.add("k", "new");

		global.mergeFrom(local);

		assertThat(global.get("k")).containsExactlyInAnyOrder("old", "new");
		assertThat(local.get("k")).isNull();
	}
}
