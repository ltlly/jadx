package jadx.tests.integration.inner;

import java.io.File;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.MethodThrowsAttr;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.ProcessState;
import jadx.core.dex.nodes.RootNode;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;

public class TestAnonymousPendingThrows extends SmaliTest {

	@Test
	public void refreshTopAnonymousThrowsBeforeInliningPreparedBody() {
		String dir = "src/test/smali/inner/TestAnonymousPendingThrows/";
		jadxDecompiler = loadFiles(Arrays.asList(
				new File(dir, "Consumer.smali"),
				new File(dir, "Callback.smali"),
				new File("src/test/smali/others/TestMethodThrowsLateSource.smali")));
		RootNode root = jadxDecompiler.getRoot();
		ClassNode consumer = root.resolveClass("inner.PendingThrowsConsumer");
		ClassNode callback = root.resolveClass("inner.PendingThrowsCallback");
		ClassNode source = root.resolveClass("others.TestMethodThrowsLateSource");
		MethodNode call = callback.searchMethodByShortId("call()Ljava/lang/Object;");
		assertThat(callback.getTopParentClass()).isSameAs(callback);
		assertThat(callback.contains(AType.ANONYMOUS_CLASS)).isTrue();
		assertThat(consumer.getCodegenDeps()).contains(callback);

		root.getProcessClasses().forceProcess(callback);
		MethodThrowsAttr pending = call.get(AType.METHOD_THROWS);
		assertThat(pending.isVisited()).isFalse();
		assertThat(pending.getList()).isEmpty();
		assertThat(pending.getPendingInfo().getCalls())
				.anyMatch(fact -> fact.getMethod().equals(consumer.searchMethodByShortId("fail()V").getMethodInfo()));
		root.getProcessClasses().forceProcess(consumer);
		assertThat(consumer.getState()).isEqualTo(ProcessState.PROCESS_COMPLETE);
		assertThat(source.searchMethodByShortId("fail()V").getBasicBlocks()).isNull();
		root.getProcessClasses().forceProcess(source);
		assertThat(source.searchMethodByShortId("fail()V").get(AType.METHOD_THROWS).isVisited()).isTrue();
		assertThat(call.get(AType.METHOD_THROWS)).isSameAs(pending);

		assertThat(consumer.getCode().getCodeStr())
				.contains("new Callable()", "Object call() throws FileNotFoundException", "void fail() throws FileNotFoundException")
				.doesNotContain("JADX ERROR", "Method not decompiled");
		assertThat(call.get(AType.METHOD_THROWS).getList()).containsExactly("java.io.FileNotFoundException");
		assertThat(pending.isVisited()).isFalse();
		assertThat(pending.getList()).isEmpty();
		runChecks(Arrays.asList(consumer, source));
	}
}
