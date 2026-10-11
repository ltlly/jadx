package jadx.core.dex.trycatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.tests.api.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class TryEdgeScopeGroupMapTest extends IntegrationTest {

	public static class TestCls {
		public int test(int value) {
			return value;
		}
	}

	@Test
	void isolatedScopeSurvivesAlreadyGroupedEdges() {
		MethodNode mth = getMethod(getClassNode(TestCls.class), "test");
		List<BlockNode> blocks = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			blocks.add(new BlockNode(i, i, i));
		}
		mth.setBasicBlocks(blocks);
		TryCatchBlockAttr tryBlock = new TryCatchBlockAttr(0, List.of(), List.of(blocks.get(0)));
		TryEdgeScopeGroupMap scopes = new TryEdgeScopeGroupMap(mth, tryBlock, 3);
		for (int i = 1; i <= 3; i++) {
			BlockNode target = blocks.get(i);
			blocks.get(0).getSuccessors().add(target);
			target.getPredecessors().add(blocks.get(0));
			scopes.put(new TryEdge(blocks.get(0), target, TryEdgeType.PREMATURE_EXIT), new HashMap<>());
		}
		List<TryEdge> edges = new ArrayList<>(scopes.keySet());
		// Make the first visited scope isolated, independent of enum identity hashes.
		TryEdge isolated = edges.get(0);
		BlockNode join = blocks.get(4);
		for (TryEdge edge : edges.subList(1, 3)) {
			edge.getTarget().getSuccessors().add(join);
			join.getPredecessors().add(edge.getTarget());
		}
		blocks.forEach(BlockNode::updateCleanSuccessors);
		for (TryEdge edge : edges) {
			for (TryEdge other : edges) {
				if (edge != other) {
					scopes.get(edge).put(other, edge == isolated || other == isolated ? null : join);
				}
			}
		}

		Map<BlockNode, List<TryEdge>> groups = scopes.getScopeEnds(mth);
		assertThat(groups).containsOnlyKeys(join, isolated.getTarget());
		assertThat(groups.get(join)).containsExactlyInAnyOrder(edges.get(1), edges.get(2));
		assertThat(groups.get(isolated.getTarget())).containsExactly(isolated);
	}
}
