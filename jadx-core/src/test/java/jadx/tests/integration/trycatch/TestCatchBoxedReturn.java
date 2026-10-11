package jadx.tests.integration.trycatch;

import org.junit.jupiter.api.Test;

import jadx.tests.api.SmaliTest;

public class TestCatchBoxedReturn extends SmaliTest {

	@Test
	public void testSharedNullReturn() {
		forceDecompiledCheck();
		getClassNodeFromSmali();
	}
}
