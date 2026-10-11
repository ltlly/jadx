package jadx.tests.integration.trycatch;

import org.junit.jupiter.api.Test;

import jadx.tests.api.SmaliTest;

import static jadx.tests.api.utils.assertj.JadxAssertions.assertThat;

public class TestFinallyDivisionState extends SmaliTest {

	@Test
	public void test() {
		forceDecompiledCheck();
		assertThat(getClassNodeFromSmali()).code().countString(2, "} finally {");
	}
}
