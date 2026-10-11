package jadx.core.dex.visitors.finaly;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.trycatch.ExceptionHandler;
import jadx.core.dex.trycatch.TryCatchBlockAttr;
import jadx.core.dex.trycatch.TryEdgeScopeGroupMap;

import static org.assertj.core.api.Assertions.assertThat;

class MarkFinallyEmptyHandlerTest {

	@Test
	void keepsHandlerWithEmptyScopeAndNoRethrow() throws ReflectiveOperationException {
		ExceptionHandler handler = newHandler();
		assertThat(attemptRemove(Collections.emptyList(), newTryInfo(handler, false))).isFalse();
		assertKept(handler);
	}

	@Test
	void keepsHandlerWithOnlyEmptyBlocksAndNoRethrow() throws ReflectiveOperationException {
		ExceptionHandler handler = newHandler();
		List<BlockNode> cutBlocks = List.of(handler.getHandlerBlock(), new BlockNode(1, 1, 1));
		assertThat(attemptRemove(cutBlocks, newTryInfo(handler, false))).isFalse();
		assertKept(handler);
	}

	@Test
	void removesEmptyScopeAfterVerifiedRethrowWasCut() throws ReflectiveOperationException {
		ExceptionHandler handler = newHandler();
		InsnNode caught = new InsnNode(InsnType.MOVE_EXCEPTION, 0);
		caught.setResult(InsnArg.reg(0, ArgType.THROWABLE));
		handler.getHandlerBlock().getInstructions().add(caught);
		Object tryInfo = newTryInfo(handler, true);
		assertThat(attemptRemove(List.of(new BlockNode(1, 1, 1)), tryInfo)).isTrue();
		assertThat(handler.isRemoved()).isTrue();
		assertThat(handler.getTryBlock().getHandlers()).isEmpty();
	}

	private static ExceptionHandler newHandler() {
		ExceptionHandler handler = ExceptionHandler.build(null, 0, null);
		BlockNode entry = new BlockNode(0, 0, 0);
		handler.setHandlerBlock(entry);
		handler.addBlock(entry);
		return handler;
	}

	private static void assertKept(ExceptionHandler handler) {
		assertThat(handler.isRemoved()).isFalse();
		assertThat(handler.getHandlerBlock().contains(AFlag.REMOVE)).isFalse();
		assertThat(handler.getTryBlock().getHandlers()).containsExactly(handler);
	}

	@SuppressWarnings("unchecked")
	private static Object newTryInfo(ExceptionHandler handler, boolean rethrow) throws ReflectiveOperationException {
		TryCatchBlockAttr tryBlock = new TryCatchBlockAttr(0, new ArrayList<>(List.of(handler)), Collections.emptyList());
		Class<?> infoClass = Class.forName(MarkFinallyVisitor.class.getName() + "$TryExtractInfo");
		Constructor<?> constructor = infoClass.getDeclaredConstructor(TryCatchBlockAttr.class, TryEdgeScopeGroupMap.class,
				ExceptionHandler.class, Map.class, TryCatchEdgeBlockMap.class);
		constructor.setAccessible(true);
		Object info = constructor.newInstance(tryBlock, new TryEdgeScopeGroupMap(null, tryBlock, 0),
				handler, Collections.emptyMap(), new TryCatchEdgeBlockMap());
		if (rethrow) {
			BlockNode rethrowBlock = new BlockNode(2, 2, 2);
			InsnNode insn = new InsnNode(InsnType.THROW, 1);
			insn.addArg(handler.getHandlerBlock().getInstructions().get(0).getResult().duplicate());
			rethrowBlock.getInstructions().add(insn);
			handler.addBlock(rethrowBlock);
			Field field = infoClass.getDeclaredField("rethrowBlocks");
			field.setAccessible(true);
			((Set<BlockNode>) field.get(info)).add(rethrowBlock);
		}
		return info;
	}

	private static boolean attemptRemove(List<BlockNode> cutBlocks, Object tryInfo) throws ReflectiveOperationException {
		Method method = MarkFinallyVisitor.class.getDeclaredMethod("attemptRemoveImplicitHandlers", List.class, tryInfo.getClass());
		method.setAccessible(true);
		return (boolean) method.invoke(null, cutBlocks, tryInfo);
	}
}
