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
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.ProcessState;
import jadx.core.dex.nodes.RootNode;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;

public class TestAnonymousClassUnload extends SmaliTest {

	@Test
	public void test() {
		String dir = "src/test/smali/inner/TestAnonymousClassUnload/";
		jadxDecompiler = loadFiles(Arrays.asList(
				new File(dir, "Owner.smali"),
				new File(dir, "Owner$1.smali"),
				new File(dir, "Consumer.smali")));
		RootNode root = jadxDecompiler.getRoot();
		ClassNode owner = root.resolveClass("inner.Owner");
		ClassNode anonymous = root.resolveClass("inner.Owner$1");
		ClassNode consumer = root.resolveClass("inner.Consumer");
		assertThat(anonymous.getTopParentClass()).isSameAs(owner);

		ProcessClass process = root.getProcessClasses();
		process.forceProcess(owner);
		process.forceProcess(consumer);
		AtomicBoolean unloadOwner = new AtomicBoolean(true);
		AtomicReference<ICodeInfo> ownerCode = new AtomicReference<>();
		Function<JadxArgs, ICodeWriter> writerProvider = getArgs().getCodeWriterProvider();
		getArgs().setCodeWriterProvider(args -> {
			if (unloadOwner.compareAndSet(true, false)) {
				// Run another class after dependency preparation, before consumer code generation.
				ownerCode.set(owner.decompile());
				assertThat(owner.getState()).isEqualTo(ProcessState.GENERATED_AND_UNLOADED);
				assertThat(anonymous.getMethods()).allMatch(mth -> !mth.isLoaded());
			}
			return writerProvider.apply(args);
		});

		// Ordinary integration checks keep classes loaded and mask this interleaving.
		ICodeInfo code = consumer.getCode();
		assertThat(code.getCodeStr())
				.contains("return new ")
				.doesNotContain("JADX ERROR", "Method not decompiled");
		assertThat(ownerCode.get()).isNotNull();
		assertThat(ownerCode.get().getCodeStr() + code.getCodeStr()).contains("System.out.println(");
		assertThat(jadxDecompiler.getErrorsCount()).isZero();
	}
}
