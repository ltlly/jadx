package jadx.tests.integration.trycatch;

import org.junit.jupiter.api.Test;

import jadx.tests.api.SmaliTest;

import static jadx.tests.api.utils.assertj.JadxAssertions.assertThat;

public class TestMixedCaughtThrowable extends SmaliTest {

	@Test
	public void test() {
		assertThat(getClassNodeFromSmali()).code()
				.containsOne("supplied(boolean replace, Throwable alternative) throws Throwable")
				.containsOne("fresh(boolean replace) throws Throwable");
	}
}
