package jadx.tests.integration.trycatch;

import org.junit.jupiter.api.Test;

import jadx.tests.api.SmaliTest;

public class TestSharedCatchNestedCleanup extends SmaliTest {

	@Test
	public void testBothHandlersRunNestedCleanup() {
		forceDecompiledCheck();
		getClassNodeFromSmali();
	}
}
