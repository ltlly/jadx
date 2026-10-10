package jadx.tests.integration.conditions;

import org.junit.jupiter.api.Test;

import jadx.core.dex.nodes.ClassNode;
import jadx.tests.api.SmaliTest;

import static jadx.tests.api.utils.assertj.JadxAssertions.assertThat;

/**
 * Chained redundant `else` blocks: each `if (cond) return x; else <rest>` level
 * forces one rewrite in {@code RemoveRedundantElseVisitor}, so the traversal
 * has to perform many rewrites for a single method.
 * <p>
 * Expected code was captured from the original restart-from-root traversal
 * and must stay byte-identical with the work-set traversal.
 */
public class TestRedundantElseChain extends SmaliTest {

	@Test
	public void test() {
		disableCompilation();
		ClassNode cls = getClassNodeFromSmaliWithPkg("conditions", "TestRedundantElseChain");
		String code = cls.getCode().getCodeStr();
		assertThat(code).contains(getExpectedBody(10, "TestRedundantElseChain"));
	}

	@Test
	public void testDeepChain() {
		disableCompilation();
		ClassNode cls = getClassNodeFromSmaliWithPkg("conditions", "TestRedundantElseChainBig");
		String code = cls.getCode().getCodeStr();
		assertThat(code).contains(getExpectedBody(200, "TestRedundantElseChainBig"));
	}

	/**
	 * Build the exact expected method body for a chain of the given length:
	 * a sequence of single-return ifs and a final ternary.
	 */
	private static String getExpectedBody(int chainLen, String clsName) {
		StringBuilder sb = new StringBuilder();
		sb.append("public final class ").append(clsName).append(" {\n");
		sb.append("    public static int test(int i) {\n");
		for (int i = 0; i < chainLen - 1; i++) {
			sb.append("        if (i != 0) {\n");
			sb.append("            return ").append(i).append(";\n");
			sb.append("        }\n");
		}
		sb.append("        return i != 0 ? ").append(chainLen - 1).append(" : -1;\n");
		sb.append("    }\n");
		sb.append("}");
		return sb.toString();
	}
}
