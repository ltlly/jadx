package jadx.tests.integration.inner;

import java.io.File;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;

public class TestAnonymousStaticCaller extends SmaliTest {

	@Test
	public void testCapturedOwnersWithOwnerFirst() {
		check(true);
	}

	@Test
	public void testCapturedOwnersWithCallerFirst() {
		check(false);
	}

	private void check(boolean ownerFirst) {
		String dir = "src/test/smali/inner/TestAnonymousStaticCaller/";
		jadxDecompiler = loadFiles(Arrays.asList(
				new File(dir, "StaticAnonymousOwner.smali"),
				new File(dir, "StaticAnonymousOwner$1.smali"),
				new File(dir, "StaticAnonymousOwner$2.smali"),
				new File(dir, "StaticAnonymousOwner$3.smali"),
				new File(dir, "StaticAnonymousCaller.smali")));
		RootNode root = jadxDecompiler.getRoot();
		ClassNode owner = root.resolveClass("inner.StaticAnonymousOwner");
		ClassNode anonymous = root.resolveClass("inner.StaticAnonymousOwner$1");
		ClassNode caller = root.resolveClass("inner.StaticAnonymousCaller");
		assertThat(anonymous.getTopParentClass()).isSameAs(owner);
		if (ownerFirst) {
			owner.getCode();
		}
		String callerCode = caller.getCode().getCodeStr();
		String ownerCode = owner.getCode().getCodeStr();
		runChecks(Arrays.asList(owner, caller));
		runDecompiledAutoCheck(owner);
		assertThat(callerCode)
				.contains("new StaticAnonymousOwner.AnonymousClass1(value)")
				.contains("owner.new AnonymousClass2(value)")
				.contains("new StaticAnonymousOwner.AnonymousClass3(value, data)")
				.doesNotContain("JADX ERROR", "Method not decompiled");
		assertThat(ownerCode)
				.contains("static class AnonymousClass1")
				.contains("StaticAnonymousOwner.lastValue = this.value")
				.contains("class AnonymousClass2")
				.contains("static class AnonymousClass3")
				.contains("this.data.instanceValue = this.value")
				.doesNotContain("static class AnonymousClass2")
				.doesNotContain("JADX ERROR", "Method not decompiled");
		assertThat(jadxDecompiler.getErrorsCount()).isZero();
		String reloadedCode = owner.reloadCode().getCodeStr();
		runChecks(Arrays.asList(owner, caller));
		runDecompiledAutoCheck(owner);
		assertThat(reloadedCode)
				.contains("static class AnonymousClass1")
				.contains("class AnonymousClass2")
				.contains("static class AnonymousClass3")
				.contains("this.data.instanceValue = this.value")
				.doesNotContain("static class AnonymousClass2", "JADX ERROR", "Method not decompiled");
	}
}
