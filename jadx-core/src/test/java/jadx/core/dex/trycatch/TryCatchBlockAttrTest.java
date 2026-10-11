package jadx.core.dex.trycatch;

import java.util.BitSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.LoopInfo;
import jadx.core.dex.nodes.BlockNode;

import static org.assertj.core.api.Assertions.assertThat;

public class TryCatchBlockAttrTest {
	@Test
	public void loopBackEdgeDoesNotHideTryExit() {
		BlockNode entry = block(0, 0);
		BlockNode header = block(8, 1, 0);
		BlockNode top = block(34, 2, 0, 1);
		BlockNode first = block(10, 3, 0, 1, 2);
		BlockNode end = block(16, 4, 0, 1, 2, 3);
		BlockNode cleanup = block(22, 5, 0, 1);
		BlockNode handlerBlock = block(19, 6, 0, 1, 2);
		BlockNode exit = block(99, 7, 0, 1);
		connect(entry, header);
		connect(header, top, cleanup);
		connect(top, first, handlerBlock);
		connect(first, end, cleanup);
		connect(end, header);
		connect(cleanup, exit);
		connect(handlerBlock, exit);
		header.add(AFlag.LOOP_START);
		end.add(AFlag.LOOP_END);
		LoopInfo loop = new LoopInfo(header, end, Set.of(header, top, first, end));
		header.addAttr(AType.LOOP, loop);
		end.addAttr(AType.LOOP, loop);
		ExceptionHandler handler = ExceptionHandler.build(null, 0, null);
		handler.setHandlerBlock(handlerBlock);
		handler.addBlock(handlerBlock);
		handlerBlock.addAttr(new ExcHandlerAttr(handler));
		List.of(entry, header, top, first, end, cleanup, handlerBlock, exit).forEach(BlockNode::updateCleanSuccessors);

		for (List<BlockNode> protectedBlocks : List.of(List.of(first, end), List.of(end, first))) {
			TryCatchBlockAttr tryBlock = new TryCatchBlockAttr(0, List.of(handler), protectedBlocks);
			tryBlock.setTopSplitter(top);
			List<TryEdge> edges = tryBlock.getFallthroughTryEdges();
			assertThat(edges).anyMatch(edge -> edge.getType() == TryEdgeType.LOOP_EXIT && edge.getTarget() == end);
			assertThat(edges).noneMatch(edge -> edge.getTarget() == handlerBlock);
		}
	}

	@Test
	public void loopInsideTryKeepsBreakExit() {
		BlockNode header = block(0, 0);
		BlockNode body = block(1, 1, 0);
		BlockNode end = block(2, 2, 0, 1);
		BlockNode cleanup = block(3, 3, 0);
		BlockNode exit = block(4, 4, 0, 3);
		connect(header, body, cleanup);
		connect(body, end, cleanup);
		connect(end, header);
		connect(cleanup, exit);
		header.add(AFlag.LOOP_START);
		end.add(AFlag.LOOP_END);
		LoopInfo loop = new LoopInfo(header, end, Set.of(header, body, end));
		header.addAttr(AType.LOOP, loop);
		end.addAttr(AType.LOOP, loop);
		List.of(header, body, end, cleanup, exit).forEach(BlockNode::updateCleanSuccessors);
		TryCatchBlockAttr tryBlock = new TryCatchBlockAttr(0, List.of(), List.of(header, body, end));
		tryBlock.setTopSplitter(header);

		List<TryEdge> edges = tryBlock.getFallthroughTryEdges();
		assertThat(edges).anyMatch(edge -> edge.getTarget() == cleanup);
		assertThat(edges).noneMatch(edge -> edge.getType() == TryEdgeType.LOOP_EXIT);
	}

	@Test
	public void exceptionHandlerIsNotFallthrough() {
		BlockNode body = block(0, 0);
		BlockNode cleanup = block(1, 1, 0);
		BlockNode handlerBlock = block(2, 2, 0);
		connect(body, cleanup, handlerBlock);
		ExceptionHandler handler = ExceptionHandler.build(null, 0, null);
		handler.setHandlerBlock(handlerBlock);
		handler.addBlock(handlerBlock);
		handlerBlock.addAttr(new ExcHandlerAttr(handler));
		List.of(body, cleanup, handlerBlock).forEach(BlockNode::updateCleanSuccessors);
		TryCatchBlockAttr tryBlock = new TryCatchBlockAttr(0, List.of(handler), List.of(body));
		tryBlock.setTopSplitter(body);

		List<TryEdge> edges = tryBlock.getFallthroughTryEdges();
		assertThat(edges).anyMatch(edge -> edge.getTarget() == cleanup);
		assertThat(edges).noneMatch(edge -> edge.getTarget() == handlerBlock);
	}

	private static BlockNode block(int id, int pos, int... dominators) {
		BlockNode block = new BlockNode(id, pos, pos);
		BitSet doms = new BitSet();
		for (int dominator : dominators) {
			doms.set(dominator);
		}
		block.setDoms(doms);
		return block;
	}

	private static void connect(BlockNode from, BlockNode... targets) {
		for (BlockNode target : targets) {
			from.getSuccessors().add(target);
			target.getPredecessors().add(from);
		}
	}
}
