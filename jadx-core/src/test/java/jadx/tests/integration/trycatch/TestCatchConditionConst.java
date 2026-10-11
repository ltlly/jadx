package jadx.tests.integration.trycatch;

import org.junit.jupiter.api.Test;

import jadx.tests.api.SmaliTest;

public class TestCatchConditionConst extends SmaliTest {

	@Test
	public void test() {
		allowWarnInCode(); // The shared false branch produces a duplicated region warning.
		forceDecompiledCheck();
		getClassNodeFromSmali();
	}
}
