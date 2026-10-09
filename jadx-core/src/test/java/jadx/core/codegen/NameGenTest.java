package jadx.core.codegen;

import java.util.Set;

import org.junit.jupiter.api.Test;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.args.NamedArg;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.tests.api.utils.TestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class NameGenTest {
	@Test
	void testRootPackagesAndInheritedNames() {
		JadxArgs args = new JadxArgs();
		args.addInputFile(TestUtils.getFileForSample("test-samples/hello.dex"));
		try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
			decompiler.load();
			RootNode root = decompiler.getRoot();
			ClassNode cls = root.getClasses().get(0);
			MethodNode mth = cls.getMethods().get(0);
			ClassGen classGen = new ClassGen(cls, args);
			root.getCacheStorage().setRootPkgs(Set.of("root", "root2"));
			NameGen outer = new NameGen(mth, classGen);
			assertThat(assign(outer, "root")).isEqualTo("root3");

			NameGen inner = new NameGen(mth, classGen);
			inner.inheritUsedNames(outer);
			assertThat(assign(inner, "root3")).isEqualTo("root4");
			assertThat(assign(inner, "root")).isEqualTo("root5");

			root.getCacheStorage().setRootPkgs(Set.of("other"));
			NameGen updated = new NameGen(mth, classGen);
			updated.inheritUsedNames(outer);
			assertThat(assign(updated, "root")).isEqualTo("root4");
			assertThat(assign(updated, "other")).isEqualTo("other2");
			assertThat(assign(outer, "other")).isEqualTo("other");
		}
	}

	private static String assign(NameGen nameGen, String name) {
		return nameGen.assignNamedArg(new NamedArg(name, ArgType.STRING));
	}
}
