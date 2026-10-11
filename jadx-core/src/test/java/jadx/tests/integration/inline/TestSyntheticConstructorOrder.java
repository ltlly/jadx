package jadx.tests.integration.inline;

import java.io.File;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import jadx.core.dex.attributes.AType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;

public class TestSyntheticConstructorOrder extends SmaliTest {

	@Test
	public void testConstructorBeforeCaller() {
		checkConstructor(false);
	}

	@Test
	public void testCallerBeforeConstructorCodegen() {
		checkConstructor(true);
	}

	private void checkConstructor(boolean callerFirst) {
		String dir = "src/test/smali/inline/TestSyntheticConstructorOrder/";
		jadxDecompiler = loadFiles(Arrays.asList(
				new File(dir, "SyntheticValue.smali"),
				new File(dir, "ConstructorUser.smali")));
		RootNode root = jadxDecompiler.getRoot();
		ClassNode value = root.resolveClass("inline.SyntheticValue");
		ClassNode user = root.resolveClass("inline.ConstructorUser");
		MethodNode constructor = value.searchMethodByShortId("<init>(Ljava/lang/String;)V");
		assertThat(value.get(AType.ANONYMOUS_CLASS)).isNull();
		assertThat(constructor.getUseIn()).containsExactly(user.searchMethodByShortId("value()Ljava/lang/String;"));
		root.getProcessClasses().forceProcess(value);
		if (callerFirst) {
			root.getProcessClasses().forceProcess(user);
		}
		assertThat(user.getState().isProcessComplete()).isEqualTo(callerFirst);
		// A constructor call cannot consume a regular method-inline instruction.
		assertThat(value.getCode().getCodeStr())
				.contains("SyntheticValue(String", "this.value = ")
				.doesNotContain("JADX ERROR", "Method not decompiled");
		assertThat(user.getCode().getCodeStr()).contains("new SyntheticValue(\"kept\")");
		runChecks(Arrays.asList(value, user));
	}
}
