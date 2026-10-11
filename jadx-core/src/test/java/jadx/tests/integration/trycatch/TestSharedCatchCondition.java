package jadx.tests.integration.trycatch;

import org.junit.jupiter.api.Test;

import jadx.tests.api.SmaliTest;

public class TestSharedCatchCondition extends SmaliTest {

	@Test
	public void testSharedConditionWithReturnsInBothHandlers() {
		allowWarnInCode(); // The shared conditional tail is emitted in both handlers.
		forceDecompiledCheck();
		getClassNodeFromSmali();
	}
}
