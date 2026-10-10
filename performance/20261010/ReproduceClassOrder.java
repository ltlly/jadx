import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.dex.nodes.ClassNode;

/** Generate a target before or after other classes, with unmodified baseline JARs. */
public class ReproduceClassOrder {

	public static void main(String[] args) throws Exception {
		Path destination = Path.of(args[3]);
		Files.createDirectories(destination);
		for (boolean targetFirst : new boolean[] { true, false }) {
			JadxArgs options = new JadxArgs();
			options.setInputFiles(List.of(new File(args[0])));
			options.setSkipResources(true);
			options.setThreadsCount(8);
			try (JadxDecompiler jadx = new JadxDecompiler(options)) {
				jadx.load();
				ClassNode target = jadx.getRoot().resolveClass(args[1]);
				if (target == null) {
					throw new AssertionError("Class missing: " + args[1]);
				}
				String code = targetFirst ? target.getCode().getCodeStr() : null;
				for (String name : args[2].split(",")) {
					ClassNode before = jadx.getRoot().resolveClass(name);
					if (before == null) {
						throw new AssertionError("Class missing: " + name);
					}
					Path generated = destination.resolve(targetFirst ? "target-first-dependencies" : "target-last-dependencies");
					Files.createDirectories(generated);
					Files.writeString(generated.resolve(name + ".java"), before.getCode().getCodeStr());
				}
				if (!targetFirst) {
					code = target.getCode().getCodeStr();
				}
				Files.writeString(destination.resolve(targetFirst ? "target-first.java" : "target-last.java"), code);
				System.out.println("targetFirst=" + targetFirst + ", characters=" + code.length());
			}
		}
	}
}
