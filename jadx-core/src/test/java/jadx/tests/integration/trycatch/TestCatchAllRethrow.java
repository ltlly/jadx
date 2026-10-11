package jadx.tests.integration.trycatch;

import org.junit.jupiter.api.Test;

import jadx.tests.api.SmaliTest;

import static jadx.tests.api.utils.assertj.JadxAssertions.assertThat;

public class TestCatchAllRethrow extends SmaliTest {

	@Test
	public void testOnlyUncaughtCleanupRethrowPropagatesCheckedException() {
		forceDecompiledCheck();
		assertThat(getClassNodeFromSmali())
				.code()
				.containsOne("int rethrow(boolean z) throws IOException")
				.containsOne("int swallow(boolean z) {")
				.containsOne("int filtered(boolean z) {")
				.containsOne("int outerCatch(boolean z) {")
				.doesNotContain("throws Throwable");
	}
}
