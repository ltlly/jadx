package jadx.tests.integration.trycatch;

import org.junit.jupiter.api.Test;

import jadx.tests.api.SmaliTest;

public class TestCatchBoundaryMove extends SmaliTest {

	@Test
	public void test() {
		forceDecompiledCheck();
		getClassNodeFromSmali();
	}
}
