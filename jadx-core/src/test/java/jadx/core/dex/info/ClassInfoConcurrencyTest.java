package jadx.core.dex.info;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jadx.api.JadxArgs;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.RootNode;

import static org.assertj.core.api.Assertions.assertThat;

class ClassInfoConcurrencyTest {

	private RootNode root;
	private InfoStorage storage;

	@BeforeEach
	public void init() {
		root = new RootNode(new JadxArgs());
		storage = root.getInfoStorage();
	}

	@Test
	void fromTypeReturnsStoredCanonicalInstance() {
		ArgType type = ArgType.object("com.example.Cls");
		ClassInfo first = ClassInfo.fromType(root, type);
		ClassInfo second = ClassInfo.fromType(root, type);
		assertThat(second).isSameAs(first);
		assertThat(storage.getCls(type)).isSameAs(first);
	}

	/**
	 * Concurrent first interning of the same (external, inner) type must produce
	 * a single canonical instance: the object returned to every caller is the object stored
	 * in the interning map, and the outer class chain is canonical as well.
	 */
	@Test
	void canonicalObjectNotReplacedUnderConcurrentCreation() throws Exception {
		ArgType innerType = ArgType.object("com.example.Outer$Inner");
		int threads = 16;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CyclicBarrier barrier = new CyclicBarrier(threads);
		try {
			Future<ClassInfo>[] futures = new Future[threads];
			for (int i = 0; i < threads; i++) {
				futures[i] = executor.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					return ClassInfo.fromType(root, innerType);
				});
			}
			ClassInfo first = futures[0].get(10, TimeUnit.SECONDS);
			assertThat(first).isNotNull();
			ClassInfo stored = storage.getCls(innerType);
			assertThat(stored).isSameAs(first);
			for (int i = 1; i < threads; i++) {
				ClassInfo clsInfo = futures[i].get(10, TimeUnit.SECONDS);
				assertThat(clsInfo).isSameAs(stored);
				ClassInfo outer = clsInfo.getParentClass();
				assertThat(outer).isNotNull();
				assertThat(outer.getRawName()).isEqualTo("com.example.Outer");
				assertThat(outer).isSameAs(storage.getCls(ArgType.object("com.example.Outer")));
			}
			assertThat(stored.isInner()).isTrue();
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	/**
	 * Putting an equal but distinct ClassInfo must never replace the stored canonical object.
	 * The old implementation used Map.put (replacing the map entry while returning the previous
	 * instance), so the map ended up holding the new object while callers kept the old one.
	 */
	@Test
	void putClsDoesNotReplaceStoredCanonical() {
		ArgType type = ArgType.object("com.example.Canonical");
		ClassInfo canonical = ClassInfo.fromType(root, type);
		assertThat(canonical).isNotNull();

		ClassInfo duplicate = ClassInfo.fromNameWithoutCache(root, "com.example.Canonical", false);
		assertThat(duplicate).isNotSameAs(canonical);
		assertThat(duplicate).isEqualTo(canonical);

		ClassInfo returned = storage.putCls(duplicate);
		assertThat(returned).isSameAs(canonical);
		assertThat(storage.getCls(type)).isSameAs(canonical);
	}

	/**
	 * Recursive outer creation from a miss path must not deadlock on the reentrant
	 * creation monitor: interning an inner class creates its outer chain in the same call.
	 */
	@Test
	void innerClassCreationBuildsCanonicalOuterChain() {
		ClassInfo clsInfo = ClassInfo.fromName(root, "com.test.LevelA$LevelB$LevelC");
		assertThat(clsInfo.isInner()).isTrue();
		ClassInfo levelB = clsInfo.getParentClass();
		assertThat(levelB).isNotNull();
		assertThat(levelB.getRawName()).isEqualTo("com.test.LevelA$LevelB");
		ClassInfo levelA = levelB.getParentClass();
		assertThat(levelA).isNotNull();
		assertThat(levelA.isInner()).isFalse();
		assertThat(storage.getCls(ArgType.object("com.test.LevelA"))).isSameAs(levelA);
	}
}
