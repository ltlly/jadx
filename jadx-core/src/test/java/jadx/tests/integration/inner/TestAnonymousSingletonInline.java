package jadx.tests.integration.inner;

import java.io.File;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import jadx.core.dex.attributes.AType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;

public class TestAnonymousSingletonInline extends SmaliTest {

	@Test
	public void testInnerOwnerFirst() {
		checkInnerSingleton(true);
	}

	@Test
	public void testInnerCallerFirst() {
		checkInnerSingleton(false);
	}

	private void checkInnerSingleton(boolean ownerFirst) {
		RootNode root = loadSingleton("SingletonOwner", "SingletonOwner$1", "SingletonConsumer");
		ClassNode owner = root.resolveClass("inner.SingletonOwner");
		ClassNode singleton = root.resolveClass("inner.SingletonOwner$1");
		ClassNode consumer = root.resolveClass("inner.SingletonConsumer");
		assertThat(singleton.get(AType.ANONYMOUS_CLASS)).isNotNull();
		assertThat(singleton.getTopParentClass()).isSameAs(owner);
		if (ownerFirst) {
			owner.getCode();
		}
		String consumerCode = consumer.getCode().getCodeStr();
		String ownerCode = owner.getCode().getCodeStr();
		assertThat(consumerCode)
				.contains("SingletonOwner.access$000()")
				.doesNotContain(".INSTANCE", "JADX ERROR", "Method not decompiled");
		assertThat(ownerCode)
				.contains("Runnable access$000()")
				.contains("System.out.println(\"inner singleton\")")
				.doesNotContain("JADX ERROR", "Method not decompiled");
		assertThat(jadxDecompiler.getErrorsCount()).isZero();
		runChecks(Arrays.asList(owner, consumer));
	}

	@Test
	public void testTopCallerFirst() {
		RootNode root = loadSingleton("TopSingleton", "TopSingletonAccess", "TopSingletonConsumer");
		ClassNode singleton = root.resolveClass("inner.TopSingleton");
		ClassNode accessor = root.resolveClass("inner.TopSingletonAccess");
		ClassNode consumer = root.resolveClass("inner.TopSingletonConsumer");
		assertThat(singleton.get(AType.ANONYMOUS_CLASS)).isNotNull();
		assertThat(singleton.isTopClass()).isTrue();
		assertThat(consumer.getCode().getCodeStr())
				.contains("return new Runnable()")
				.contains("System.out.println(\"top singleton\")")
				.doesNotContain(".INSTANCE", "JADX ERROR", "Method not decompiled");
		assertThat(jadxDecompiler.getErrorsCount()).isZero();
		runChecks(Arrays.asList(accessor, consumer));
	}

	private RootNode loadSingleton(String first, String second, String third) {
		String dir = "src/test/smali/inner/TestAnonymousSingletonInline/";
		jadxDecompiler = loadFiles(Arrays.asList(
				new File(dir, first + ".smali"),
				new File(dir, second + ".smali"),
				new File(dir, third + ".smali")));
		return jadxDecompiler.getRoot();
	}
}
