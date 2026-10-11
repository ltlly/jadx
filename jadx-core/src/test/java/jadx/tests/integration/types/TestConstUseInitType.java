package jadx.tests.integration.types;

import org.junit.jupiter.api.Test;

import jadx.tests.api.SmaliTest;

import static jadx.tests.api.utils.assertj.JadxAssertions.assertThat;

public class TestConstUseInitType extends SmaliTest {

	@Test
	public void test() {
		forceDecompiledCheck();
		assertThat(getClassNodeFromSmali())
				.code()
				.containsOne("new TestConstUseInitType(null, null)");
	}
}
