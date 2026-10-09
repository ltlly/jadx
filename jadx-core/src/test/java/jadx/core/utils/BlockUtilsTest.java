package jadx.core.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.nodes.BlockNode;

import static org.assertj.core.api.Assertions.assertThat;

class BlockUtilsTest {
	@Test
	void testAnyPathBlockMatches() {
		// All three-node graphs include disconnected entries, cycles, self edges and joins.
		for (int edges = 0; edges < 1 << 9; edges++) {
			List<BlockNode> blocks = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				blocks.add(new BlockNode(i, i, i));
			}
			blocks.get(2).add(AFlag.EXC_BOTTOM_SPLITTER);
			for (int from = 0; from < 3; from++) {
				for (int to = 0; to < 3; to++) {
					if ((edges & (1 << (from * 3 + to))) != 0) {
						blocks.get(from).getSuccessors().add(blocks.get(to));
						blocks.get(to).getPredecessors().add(blocks.get(from));
					}
				}
			}
			for (BlockNode start : blocks) {
				for (BlockNode end : blocks) {
					Set<BlockNode> expected = BlockUtils.getAllPathsBlocks(start, end);
					for (BlockNode match : blocks) {
						assertThat(BlockUtils.anyPathBlockMatches(start, end, block -> block == match))
								.as("edges=%s, start=%s, end=%s, match=%s", edges, start, end, match)
								.isEqualTo(expected.contains(match));
					}
				}
			}
		}
	}

	@Test
	void testAnyPathBlockMatchesStopsEarly() {
		BlockNode start = new BlockNode(0, 0, 0);
		BlockNode end = new BlockNode(1, 1, 1);
		end.getPredecessors().add(new BlockNode(2, 2, 2));
		AtomicInteger visits = new AtomicInteger();
		assertThat(BlockUtils.anyPathBlockMatches(start, end, block -> {
			visits.incrementAndGet();
			return block == end;
		})).isTrue();
		assertThat(visits.get()).isEqualTo(2);
	}
}
