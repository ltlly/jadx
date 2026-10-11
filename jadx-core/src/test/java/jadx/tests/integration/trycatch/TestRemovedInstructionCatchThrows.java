package jadx.tests.integration.trycatch;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import jadx.api.JadxInternalAccess;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.instructions.InvokeNode;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.trycatch.CatchAttr;
import jadx.core.dex.visitors.AbstractVisitor;
import jadx.core.dex.visitors.IDexTreeVisitor;
import jadx.tests.api.SmaliTest;

import static jadx.tests.api.utils.assertj.JadxAssertions.assertThat;

public class TestRemovedInstructionCatchThrows extends SmaliTest {

	@Test
	public void test() {
		forceDecompiledCheck();
		jadxDecompiler = loadFiles(Collections.singletonList(new File("src/test/smali/trycatch/TestRemovedInstructionCatchThrows.smali")));
		RootNode root = JadxInternalAccess.getRoot(jadxDecompiler);
		ClassNode cls = root.resolveClass("trycatch.TestRemovedInstructionCatchThrows");
		AtomicBoolean movedWithRemovedCatchAll = new AtomicBoolean();
		List<IDexTreeVisitor> passes = root.getPasses();
		int index = -1;
		for (int i = 0; i < passes.size(); i++) {
			if (passes.get(i).getName().equals("MethodThrowsVisitor")) {
				index = i;
				break;
			}
		}
		assertThat(index).isNotNegative();
		passes.add(index, new AbstractVisitor() {
			@Override
			public void visit(MethodNode mth) {
				if (!mth.getMethodInfo().getShortId().equals("get()Ljava/lang/Object;")) {
					return;
				}
				for (BlockNode block : mth.getBasicBlocks()) {
					if (block.get(AType.EXC_CATCH) != null) {
						continue;
					}
					for (InsnNode insn : block.getInstructions()) {
						CatchAttr catchAttr = insn.get(AType.EXC_CATCH);
						if (insn instanceof InvokeNode && catchAttr != null
								&& ((InvokeNode) insn).getCallMth().getDeclClass().getRawName().equals("java.lang.reflect.Constructor")
								&& ((InvokeNode) insn).getCallMth().getName().equals("newInstance")
								&& catchAttr.getHandlers().stream().anyMatch(handler -> handler.isCatchAll() && handler.isRemoved())) {
							movedWithRemovedCatchAll.set(true);
						}
					}
				}
			}
		});
		cls.add(AFlag.DONT_UNLOAD_CLASS);
		cls.decompile();
		assertThat(movedWithRemovedCatchAll.get())
				.as("the moved invoke retains a removed implicit rethrow handler")
				.isTrue();
		decompileAndCheck(Collections.singletonList(cls));
		assertThat(cls).code().contains("public Object get() throws ");
	}
}
