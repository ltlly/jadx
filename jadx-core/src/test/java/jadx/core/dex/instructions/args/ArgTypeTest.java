package jadx.core.dex.instructions.args;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static jadx.core.dex.instructions.args.ArgType.WildcardBound.SUPER;
import static jadx.core.dex.instructions.args.ArgType.generic;
import static jadx.core.dex.instructions.args.ArgType.genericType;
import static jadx.core.dex.instructions.args.ArgType.wildcard;
import static jadx.tests.api.utils.assertj.JadxAssertions.assertThat;

class ArgTypeTest {

	@Test
	public void testEqualsOfGenericTypes() {
		ArgType first = ArgType.generic("java.lang.List", ArgType.STRING);
		ArgType second = ArgType.generic("Ljava/lang/List;", ArgType.STRING);

		assertThat(first).isEqualTo(second);
	}

	@Test
	void testContainsGenericType() {
		ArgType wildcard = wildcard(genericType("T"), SUPER);
		assertThat(wildcard.containsTypeVariable()).isTrue();

		ArgType type = generic("java.lang.List", wildcard);
		assertThat(type.containsTypeVariable()).isTrue();
	}

	@Test
	void testInnerGeneric() {
		ArgType[] genericTypes = new ArgType[] { ArgType.genericType("K"), ArgType.genericType("V") };
		ArgType base = ArgType.generic("java.util.Map", genericTypes);

		ArgType genericInner = ArgType.outerGeneric(base, ArgType.generic("Entry", genericTypes));
		assertThat(genericInner.toString()).isEqualTo("java.util.Map<K, V>$Entry<K, V>");
		assertThat(genericInner.containsTypeVariable()).isTrue();

		ArgType genericInner2 = ArgType.outerGeneric(base, ArgType.object("Entry"));
		assertThat(genericInner2.toString()).isEqualTo("java.util.Map<K, V>$Entry");
		assertThat(genericInner2.containsTypeVariable()).isTrue();
	}

	@Test
	void testObjectSingletonsIdentity() {
		assertThat(ArgType.object("java.lang.Object")).isSameAs(ArgType.OBJECT);
		assertThat(ArgType.object("Ljava/lang/String;")).isSameAs(ArgType.STRING);
		assertThat(ArgType.object("java.lang.Class")).isSameAs(ArgType.CLASS);
		assertThat(ArgType.object("java.lang.Throwable")).isSameAs(ArgType.THROWABLE);
		assertThat(ArgType.object("java.lang.Exception")).isSameAs(ArgType.EXCEPTION);
	}

	@Test
	void testCachedObjectTypeEquivalentToUncached() {
		ArgType cached = ArgType.object("java.util.List");
		ArgType uncached = ArgType.objectNoCache("java.util.List");
		assertThat(cached).isEqualTo(uncached);
		assertThat(cached.hashCode()).isEqualTo(uncached.hashCode());
		assertThat(cached.getClass()).isSameAs(uncached.getClass());
		assertThat(cached.getObject()).isEqualTo("java.util.List");

		// repeated calls and descriptor form resolve to the same cached instance
		assertThat(ArgType.object("java.util.List")).isSameAs(cached);
		assertThat(ArgType.object("Ljava/util/List;")).isSameAs(cached);
	}

	@Test
	void testLongObjectNameNotCached() {
		String longName = "a.VeryLongName." + "x".repeat(1100);
		ArgType first = ArgType.object(longName);
		ArgType second = ArgType.object(longName);
		assertThat(first.getObject()).isEqualTo(longName);
		assertThat(first).isEqualTo(second);
		// over the length limit: not stored in the cache
		assertThat(first).isNotSameAs(second);
	}

	@Test
	void testObjectTypeCacheEvictionKeepsEquality() {
		String target = "a.b.EvictionTarget";
		assertThat(ArgType.object(target)).isSameAs(ArgType.object(target));
		for (int i = 0; i < 5_000; i++) {
			ArgType.object("a.b.Evict" + i);
		}
		// target slot is likely overwritten, equality must not change
		ArgType t1 = ArgType.object(target);
		ArgType t2 = ArgType.objectNoCache(target);
		assertThat(t1).isEqualTo(t2);
		assertThat(t1.hashCode()).isEqualTo(t2.hashCode());
		assertThat(t1.getClass()).isSameAs(t2.getClass());
	}

	@Test
	void testObjectTypeConcurrentInterning() throws Exception {
		int threads = 8;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CyclicBarrier barrier = new CyclicBarrier(threads);
		List<Future<ArgType>> futures = new ArrayList<>(threads);
		for (int i = 0; i < threads; i++) {
			futures.add(executor.submit(() -> {
				barrier.await(10, TimeUnit.SECONDS);
				ArgType type = null;
				for (int n = 0; n < 1_000; n++) {
					type = ArgType.object("java.util.concurrent.ConcurrentMap");
					ArgType.object("a.b.Cls" + (n % 200));
				}
				return type;
			}));
		}
		ArgType expected = ArgType.objectNoCache("java.util.concurrent.ConcurrentMap");
		for (Future<ArgType> future : futures) {
			// every caller must see a type equal to the uncached result
			assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
		}
		executor.shutdownNow();
	}

	@Test
	void testGenericTypesNotAffectedByObjectTypeCache() {
		ArgType plainList = ArgType.object("java.util.List");
		assertThat(plainList.containsGeneric()).isFalse();

		ArgType genericType = genericType("T");
		ArgType genericSecond = genericType("T");
		assertThat(genericSecond).isEqualTo(genericType);
		assertThat(genericSecond).isNotSameAs(genericType); // generics are never interned

		ArgType bounded = genericType("T", plainList);
		assertThat(bounded.containsGeneric()).isTrue();
		assertThat(ArgType.object("java.util.List").containsGeneric()).isFalse(); // no extend pollution
	}
}
