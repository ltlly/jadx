package jadx.tests.integration.inner;

import java.io.File;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import jadx.core.dex.attributes.AType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;

public class TestAnonymousNestedConstructorInline extends SmaliTest {

	@Test
	public void test() {
		String dir = "src/test/smali/inner/TestAnonymousNestedConstructorInline/";
		jadxDecompiler = loadFiles(Arrays.asList(
				new File(dir, "NestedConstructorAnonymous.smali"),
				new File(dir, "NestedConstructorAccess.smali"),
				new File(dir, "NestedConstructorConsumer.smali")));
		RootNode root = jadxDecompiler.getRoot();
		ClassNode anonymous = root.resolveClass("inner.NestedConstructorAnonymous");
		ClassNode accessor = root.resolveClass("inner.NestedConstructorAccess");
		ClassNode consumer = root.resolveClass("inner.NestedConstructorConsumer");
		assertThat(anonymous.get(AType.ANONYMOUS_CLASS)).isNotNull();
		assertThat(anonymous.isTopClass()).isTrue();
		assertThat(consumer.getCode().getCodeStr())
				.contains("NestedConstructorAccess.accept(new Runnable()")
				.contains("System.out.println(\"nested constructor\")")
				.doesNotContain("access$000()", "JADX ERROR", "Method not decompiled");
		assertThat(consumer.getCodegenDeps()).contains(anonymous);
		assertThat(jadxDecompiler.getErrorsCount()).isZero();
		runChecks(Arrays.asList(accessor, consumer));
	}
}
