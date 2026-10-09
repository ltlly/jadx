package jadx.core.dex.visitors.regions.variables;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.regions.Region;
import jadx.core.dex.trycatch.ExcHandlerAttr;
import jadx.core.dex.trycatch.ExceptionHandler;
import jadx.core.dex.trycatch.TryCatchBlockAttr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessVariablesTest {
	@Test
	void testUsePositionsAndDuplicates() {
		Region region = new Region(null);
		BlockNode[] blocks = new BlockNode[4];
		for (int i = 0; i < blocks.length; i++) {
			blocks[i] = new BlockNode(i, i, i);
			region.getSubBlocks().add(blocks[i]);
		}
		for (int check = 0; check <= blocks.length; check++) {
			BlockNode block = check < blocks.length ? blocks[check] : new BlockNode(0, 0, 0);
			UsePlace checkPlace = new UsePlace(region, block);
			for (int mask = 0; mask < 1 << blocks.length; mask++) {
				List<UsePlace> uses = new ArrayList<>();
				for (int i = 0; i < blocks.length; i++) {
					if ((mask & (1 << i)) != 0) {
						uses.add(new UsePlace(region, blocks[i]));
					}
				}
				if (!uses.isEmpty()) {
					UsePlace first = uses.get(0);
					uses.add(first);
					uses.add(first);
					uses.add(new UsePlace(first.getRegion(), first.getBlock()));
				}
				boolean expected = check < blocks.length && (mask & ((1 << check) - 1)) == 0;
				assertThat(ProcessVariables.isAllUseAfter(checkPlace, uses))
						.as("check=%s, mask=%s", check, mask).isEqualTo(expected);
			}
		}
		assertThatThrownBy(() -> ProcessVariables.isAllUseAfter(new UsePlace(region, blocks[0]), Collections.singletonList(null)))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void testNestedRegionsBeforeAndAfterDeclaration() {
		Region root = new Region(null);
		Region before = new Region(root);
		Region after = new Region(root);
		Region nested = new Region(after);
		BlockNode declaration = new BlockNode(0, 0, 0);
		BlockNode use = new BlockNode(1, 1, 1);
		root.getSubBlocks().add(before);
		root.getSubBlocks().add(declaration);
		root.getSubBlocks().add(after);
		after.getSubBlocks().add(nested);
		nested.getSubBlocks().add(use);
		UsePlace check = new UsePlace(root, declaration);
		assertThat(ProcessVariables.isAllUseAfter(check, List.of(new UsePlace(nested, use)))).isTrue();
		assertThat(ProcessVariables.isAllUseAfter(check, List.of(new UsePlace(before, use)))).isFalse();
		assertThat(ProcessVariables.isAllUseAfter(check, List.of(new UsePlace(nested, use), new UsePlace(before, use)))).isFalse();
	}

	@Test
	void testSharedExceptionHandlerWithoutParentChain() {
		ExceptionHandler handler = ExceptionHandler.build(null, 0, null);
		Region handlerRegion = new Region(null);
		BlockNode handlerBlock = new BlockNode(2, 2, 2);
		handlerRegion.getSubBlocks().add(handlerBlock);
		handlerRegion.addAttr(new ExcHandlerAttr(handler));
		handler.setHandlerRegion(handlerRegion);
		TryCatchBlockAttr tryBlock = new TryCatchBlockAttr(0, List.of(handler), List.of());
		for (int i = 0; i < 2; i++) {
			Region root = new Region(null);
			BlockNode declaration = new BlockNode(i, i, i);
			Region after = new Region(root);
			Region body = new Region(after);
			body.addAttr(tryBlock);
			after.getSubBlocks().add(body);
			root.getSubBlocks().add(declaration);
			root.getSubBlocks().add(after);
			UsePlace check = new UsePlace(root, declaration);
			assertThat(ProcessVariables.isAllUseAfter(check, List.of(new UsePlace(handlerRegion, handlerBlock)))).isTrue();
		}
	}
}
