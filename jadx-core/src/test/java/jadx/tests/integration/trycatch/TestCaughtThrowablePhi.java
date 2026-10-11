package jadx.tests.integration.trycatch;

import java.io.File;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import jadx.api.JadxInternalAccess;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.MethodThrowsAttr;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.NamedArg;
import jadx.core.dex.instructions.args.RegisterArg;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.trycatch.CatchAttr;
import jadx.core.dex.trycatch.ExcHandlerAttr;
import jadx.core.dex.visitors.MethodThrowsVisitor;
import jadx.core.utils.DebugChecks;
import jadx.tests.api.SmaliTest;

import static jadx.tests.api.utils.assertj.JadxAssertions.assertThat;

public class TestCaughtThrowablePhi extends SmaliTest {

	@Test
	public void testThrowsScan() throws Exception {
		jadxDecompiler = loadFiles(Collections.singletonList(new File("src/test/smali/trycatch/TestCaughtThrowablePhi.smali")));
		RootNode root = JadxInternalAccess.getRoot(jadxDecompiler);
		ClassNode cls = root.resolveClass("trycatch.TestCaughtThrowablePhi");
		cls.load();
		for (MethodNode method : cls.getMethods()) {
			assertThat(root.getProcessClasses().processMethodUntilVisitor(method, "MethodThrowsVisitor", false)).isTrue();
			assertThat(hasErrors(method, false)).as("pipeline checks for %s", method).isFalse();
		}
		MethodNode method = cls.searchMethodByShortId("read(Ljava/lang/String;)I");
		assertThat(assertVisibleCaughtPhi(method)).as("the real throws scan must receive the merged catch value").isTrue();
		MethodThrowsVisitor visitor = new MethodThrowsVisitor();
		visitor.init(root);
		visitor.visit(method);
		DebugChecks.checkMethod(method);
		assertThat(hasErrors(method, false)).isFalse();
		MethodThrowsAttr exceptions = method.get(AType.METHOD_THROWS);
		assertThat(exceptions).isNotNull();
		assertThat(exceptions.isVisited()).isTrue();
		assertThat(exceptions.getList()).isEmpty();
	}

	private static boolean assertVisibleCaughtPhi(MethodNode method) {
		for (BlockNode block : method.getBasicBlocks()) {
			if (block.contains(AFlag.REMOVE) || block.contains(AFlag.DONT_GENERATE)) {
				continue;
			}
			CatchAttr catches = block.get(AType.EXC_CATCH);
			if (catches != null && catches.getHandlers().stream().anyMatch(handler -> handler.isCatchAll())) {
				continue;
			}
			for (InsnNode insn : block.getInstructions()) {
				if (insn.getType() != InsnType.THROW || insn.contains(AFlag.DONT_GENERATE) || !insn.getArg(0).isRegister()) {
					continue;
				}
				RegisterArg thrown = (RegisterArg) insn.getArg(0);
				InsnNode phi = thrown.getAssignInsn();
				if (phi == null || phi.getType() != InsnType.PHI) {
					continue;
				}
				assertThat(thrown.getType()).isEqualTo(ArgType.THROWABLE);
				assertThat(phi.getArgsCount()).isEqualTo(2);
				for (InsnArg arg : phi.getArguments()) {
					assertThat(arg).isInstanceOf(RegisterArg.class);
					InsnNode move = ((RegisterArg) arg).getAssignInsn();
					assertThat(move.getType()).isEqualTo(InsnType.MOVE);
					assertThat(move.getResult().getType()).isEqualTo(ArgType.THROWABLE);
					assertThat(move.getResult().contains(AFlag.CUSTOM_DECLARE)).isTrue();
					assertThat(move.getArgsCount()).isEqualTo(1);
					assertThat(move.getArg(0)).isInstanceOf(NamedArg.class);
					ExcHandlerAttr handler = move.get(AType.EXC_HANDLER);
					assertThat(handler).isNotNull();
					assertThat(handler.getHandler().getArg()).isSameAs(move.getArg(0));
				}
				return true;
			}
		}
		return false;
	}
}
