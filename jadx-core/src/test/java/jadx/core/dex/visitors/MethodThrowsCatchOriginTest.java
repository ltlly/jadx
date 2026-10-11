package jadx.core.dex.visitors;

import java.util.ArrayList;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.PhiInsn;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.NamedArg;
import jadx.core.dex.instructions.args.RegisterArg;
import jadx.core.dex.instructions.args.SSAVar;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.trycatch.CatchAttr;
import jadx.core.dex.trycatch.ExcHandlerAttr;
import jadx.core.dex.trycatch.ExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;

class MethodThrowsCatchOriginTest {

	@Test
	void recognizesDirectCaughtThrowable() {
		assertThat(isCaught(caught(ArgType.THROWABLE))).isTrue();
	}

	@Test
	void recognizesNestedPhisAndRepeatedCaughtLeaves() {
		RegisterArg first = caught(ArgType.THROWABLE);
		RegisterArg second = caught(ArgType.THROWABLE);
		RegisterArg merged = phi(first, second).getResult();
		assertThat(isCaught(phi(merged, first, merged).getResult())).isTrue();
	}

	@Test
	void recognizesSharedLayeredPhis() {
		RegisterArg merged = caught(ArgType.THROWABLE);
		for (int i = 0; i < 32; i++) {
			merged = phi(merged, merged).getResult();
		}
		assertThat(isCaught(merged)).isTrue();
	}

	@Test
	void recognizesMoveOfTheSameNamedCatchArgument() {
		RegisterArg moved = movedCatch();
		assertThat(isCaught(moved)).isTrue();
		assertThat(isCaught(phi(moved, caught(ArgType.THROWABLE), movedCatch()).getResult())).isTrue();
	}

	@Test
	void rejectsNamedArgumentWithOnlyTheSameName() {
		RegisterArg moved = movedCatch();
		InsnNode assign = moved.getAssignInsn();
		InsnArg original = assign.getArg(0);
		NamedArg different = new NamedArg("th", ArgType.THROWABLE);
		assertThat(different).isEqualTo(original).isNotSameAs(original);
		assign.get(AType.EXC_HANDLER).getHandler().setArg(different);
		assertThat(isCaught(moved)).isFalse();
	}

	@Test
	void rejectsMoveWithoutCatchMetadata() {
		RegisterArg missingHandler = movedCatch();
		missingHandler.getAssignInsn().remove(AType.EXC_HANDLER);
		assertThat(isCaught(missingHandler)).isFalse();
		RegisterArg unmarked = movedCatch();
		unmarked.remove(AFlag.CUSTOM_DECLARE);
		assertThat(isCaught(unmarked)).isFalse();
	}

	@Test
	void rejectsMoveFromSuppliedOrTypedArgument() {
		RegisterArg supplied = movedCatch();
		InsnNode suppliedAssign = supplied.getAssignInsn();
		InsnArg suppliedArg = assigned(null, ArgType.THROWABLE);
		suppliedAssign.setArg(0, suppliedArg);
		suppliedAssign.get(AType.EXC_HANDLER).getHandler().setArg(suppliedArg);
		assertThat(isCaught(phi(movedCatch(), supplied).getResult())).isFalse();
		RegisterArg typed = movedCatch();
		InsnNode typedAssign = typed.getAssignInsn();
		NamedArg typedArg = new NamedArg("th", ArgType.object("java.io.IOException"));
		typedAssign.setArg(0, typedArg);
		typedAssign.get(AType.EXC_HANDLER).getHandler().setArg(typedArg);
		assertThat(isCaught(typed)).isFalse();
	}

	@Test
	void keepsPhiWithSuppliedThrowable() {
		RegisterArg supplied = assigned(null, ArgType.THROWABLE);
		assertThat(isCaught(phi(caught(ArgType.THROWABLE), supplied).getResult())).isFalse();
		assertThat(isCaught(phi(movedCatch(), supplied).getResult())).isFalse();
	}

	@Test
	void keepsPhiWithFreshThrowable() {
		RegisterArg fresh = assigned(new InsnNode(InsnType.NEW_INSTANCE, 0), ArgType.THROWABLE);
		assertThat(isCaught(phi(caught(ArgType.THROWABLE), fresh).getResult())).isFalse();
		assertThat(isCaught(phi(movedCatch(), fresh).getResult())).isFalse();
	}

	@Test
	void keepsPhiWithTypedCheckedException() {
		assertThat(isCaught(phi(caught(ArgType.THROWABLE), caught(ArgType.object("java.io.IOException"))).getResult())).isFalse();
	}

	@Test
	void rejectsUnmarkedCatchAndMissingAssignment() {
		assertThat(isCaught(assigned(new InsnNode(InsnType.MOVE_EXCEPTION, 0), ArgType.THROWABLE))).isFalse();
		assertThat(isCaught(InsnArg.reg(0, ArgType.THROWABLE))).isFalse();
	}

	@Test
	void rejectsEmptyPhiAndCycle() {
		assertThat(isCaught(phi().getResult())).isFalse();
		PhiInsn cyclic = phi(caught(ArgType.THROWABLE));
		cyclic.bindArg(cyclic.getResult().duplicate(), new BlockNode(1, 1, 1));
		assertThat(isCaught(cyclic.getResult())).isFalse();
	}

	@Test
	void recognizesLinearCleanupRethrow() {
		assertThat(MethodThrowsVisitor.isRethrowingHandler(linearRethrow())).isTrue();
	}

	@Test
	void rejectsFilteredRethrowAndSwallowingExit() {
		ExceptionHandler handler = linearRethrow();
		BlockNode start = handler.getHandlerBlock();
		start.getInstructions().add(new InsnNode(InsnType.IF, 0));
		// A clean successor list can omit a loop edge: the branch must still be rejected.
		assertThat(MethodThrowsVisitor.isRethrowingHandler(handler)).isFalse();
		BlockNode returned = new BlockNode(2, 2, 2);
		returned.getInstructions().add(new InsnNode(InsnType.RETURN, 0));
		start.getSuccessors().add(returned);
		start.updateCleanSuccessors();
		assertThat(MethodThrowsVisitor.isRethrowingHandler(handler)).isFalse();
	}

	@Test
	void rejectsCycleAndCaughtRethrow() {
		ExceptionHandler handler = linearRethrow();
		BlockNode start = handler.getHandlerBlock();
		BlockNode end = start.getCleanSuccessors().get(0);
		start.getSuccessors().set(0, start);
		start.updateCleanSuccessors();
		assertThat(MethodThrowsVisitor.isRethrowingHandler(handler)).isFalse();
		start.getSuccessors().set(0, end);
		start.updateCleanSuccessors();
		ExceptionHandler outer = ExceptionHandler.build(null, 10, null);
		end.getInstructions().get(0).addAttr(CatchAttr.build(new ArrayList<>(Collections.singletonList(outer))));
		assertThat(MethodThrowsVisitor.isRethrowingHandler(handler)).isFalse();
	}

	@Test
	void rejectsAnotherHandlersThrowable() {
		ExceptionHandler handler = linearRethrow();
		BlockNode end = handler.getHandlerBlock().getCleanSuccessors().get(0);
		end.getInstructions().get(0).setArg(0, movedCatch().duplicate());
		assertThat(MethodThrowsVisitor.isRethrowingHandler(handler)).isFalse();
	}

	@Test
	void rejectsRethrowWithoutHandlerMetadata() {
		ExceptionHandler handler = linearRethrow();
		InsnNode caught = handler.getHandlerBlock().getInstructions().get(0);
		caught.remove(AType.EXC_HANDLER);
		assertThat(isCaught(caught.getResult())).isTrue();
		assertThat(MethodThrowsVisitor.isRethrowingHandler(handler)).isFalse();
	}

	@Test
	void rejectsRethrowPhiWithMissingHandlerMetadata() {
		ExceptionHandler handler = linearRethrow();
		BlockNode start = handler.getHandlerBlock();
		RegisterArg known = start.getInstructions().get(0).getResult();
		RegisterArg merged = phi(known, caught(ArgType.THROWABLE)).getResult();
		BlockNode end = start.getCleanSuccessors().get(0);
		end.getInstructions().get(0).setArg(0, merged.duplicate());
		assertThat(isCaught(merged)).isTrue();
		assertThat(MethodThrowsVisitor.isRethrowingHandler(handler)).isFalse();
	}

	private static ExceptionHandler linearRethrow() {
		ExceptionHandler handler = ExceptionHandler.build(null, 0, null);
		RegisterArg arg = caught(ArgType.THROWABLE);
		arg.getAssignInsn().addAttr(new ExcHandlerAttr(handler));
		handler.setArg(arg);
		BlockNode start = new BlockNode(0, 0, 0);
		start.getInstructions().add(arg.getAssignInsn());
		handler.setHandlerBlock(start);
		BlockNode end = new BlockNode(1, 1, 1);
		InsnNode rethrow = new InsnNode(InsnType.THROW, 1);
		rethrow.addArg(arg.duplicate());
		end.getInstructions().add(rethrow);
		start.getSuccessors().add(end);
		start.updateCleanSuccessors();
		return handler;
	}

	private static boolean isCaught(RegisterArg arg) {
		return MethodThrowsVisitor.isCaughtThrowable(arg);
	}

	private static RegisterArg caught(ArgType type) {
		RegisterArg result = assigned(new InsnNode(InsnType.MOVE_EXCEPTION, 0), type);
		result.add(AFlag.CUSTOM_DECLARE);
		return result;
	}

	private static RegisterArg movedCatch() {
		InsnNode move = new InsnNode(InsnType.MOVE, 1);
		NamedArg source = new NamedArg("th", ArgType.THROWABLE);
		move.addArg(source);
		ExceptionHandler handler = ExceptionHandler.build(null, 0, null);
		handler.setArg(source);
		move.addAttr(new ExcHandlerAttr(handler));
		RegisterArg result = assigned(move, ArgType.THROWABLE);
		result.add(AFlag.CUSTOM_DECLARE);
		return result;
	}

	private static RegisterArg assigned(InsnNode insn, ArgType type) {
		RegisterArg result = InsnArg.reg(0, type);
		if (insn != null) {
			insn.setResult(result);
		}
		new SSAVar(0, 0, result).setType(type);
		return result;
	}

	private static PhiInsn phi(RegisterArg... args) {
		PhiInsn phi = new PhiInsn(0, args.length);
		assigned(phi, ArgType.THROWABLE);
		for (int i = 0; i < args.length; i++) {
			phi.bindArg(args[i].duplicate(), new BlockNode(i, i, i));
		}
		return phi;
	}
}
