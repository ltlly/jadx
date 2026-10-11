package jadx.tests.integration.inner;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import jadx.api.ICodeInfo;
import jadx.api.ICodeWriter;
import jadx.api.JadxArgs;
import jadx.core.ProcessClass;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;

public class TestAnonymousInvokeCustomInline extends SmaliTest {

	@Test
	public void test() {
		String dir = "src/test/smali/inner/TestAnonymousInvokeCustomInline/";
		jadxDecompiler = loadFiles(Arrays.asList(
				new File(dir, "LambdaWrapper.smali"),
				new File(dir, "LambdaConsumer.smali")));
		RootNode root = jadxDecompiler.getRoot();
		ClassNode wrapper = root.resolveClass("inner.LambdaWrapper");
		ClassNode consumer = root.resolveClass("inner.LambdaConsumer");
		ProcessClass process = root.getProcessClasses();
		process.forceProcess(wrapper);
		process.forceProcess(consumer);
		AtomicBoolean unloadWrapper = new AtomicBoolean(true);
		AtomicReference<ICodeInfo> wrapperCode = new AtomicReference<>();
		Function<JadxArgs, ICodeWriter> writerProvider = getArgs().getCodeWriterProvider();
		getArgs().setCodeWriterProvider(args -> {
			if (unloadWrapper.compareAndSet(true, false)) {
				wrapperCode.set(wrapper.getCode());
				assertThat(wrapper.getMethods()).allMatch(mth -> !mth.isLoaded());
			}
			return writerProvider.apply(args);
		});

		assertThat(consumer.getCode().getCodeStr())
				.contains("LambdaWrapper.access$000()")
				.doesNotContain("JADX ERROR", "Method not decompiled");
		assertThat(wrapperCode.get()).isNotNull();
		assertThat(wrapperCode.get().getCodeStr())
				.contains("Supplier access$000()")
				.contains("\"lambda body\"")
				.doesNotContain("JADX ERROR", "Method not decompiled");
		assertThat(wrapper.searchMethodByShortName("access$000").get(AType.METHOD_INLINE).notNeeded()).isTrue();
		assertThat(jadxDecompiler.getErrorsCount()).isZero();
		runChecks(Arrays.asList(wrapper, consumer));
	}
}
