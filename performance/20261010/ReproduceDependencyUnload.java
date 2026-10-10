import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jadx.api.ICodeInfo;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.ProcessClass;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;

/** Replays a legal gap between dependency processing and consumer code generation. */
public class ReproduceDependencyUnload {
	public static void main(String[] args) throws Exception {
		Path destination = Path.of(args[1]);
		Files.createDirectories(destination);
		String consumerName = args.length > 2 ? args[2] : "j0";
		String prefix = "com.ucpro.feature.setting.developer.customize.";
		if (!consumerName.contains(".")) {
			consumerName = prefix + consumerName;
		}
		String dependencyName = args.length > 3 ? args[3] : prefix + "CleanTestConfigFactory";
		for (boolean interleave : new boolean[] {false, true}) {
			JadxArgs options = new JadxArgs();
			options.setInputFiles(List.of(new File(args[0])));
			options.setSkipResources(true);
			options.setThreadsCount(8);
			try (JadxDecompiler jadx = new JadxDecompiler(options)) {
				jadx.load();
				RootNode root = jadx.getRoot();
				ClassNode consumer = root.resolveClass(consumerName);
				ClassNode dependency = root.resolveClass(dependencyName);
				if (consumer == null || dependency == null) {
					throw new AssertionError("Expected Qwen classes missing");
				}
				ProcessClass process = root.getProcessClasses();
				// Same dependency-processing steps as ProcessClass.generateCode.
				for (ClassNode dep : consumer.getDependencies()) {
					process.forceProcess(dep);
				}
				if (!consumer.getCodegenDeps().isEmpty()) {
					process.forceProcess(consumer);
					for (ClassNode dep : consumer.getCodegenDeps()) {
						process.forceProcess(dep);
					}
				}
				if (interleave) {
					process.generateCode(dependency);
					System.out.println("Dependency state before consumer codegen: " + dependency.getState());
				}
				ICodeInfo code = process.forceGenerateCode(consumer);
				String text = code.getCodeStr();
				Files.writeString(destination.resolve(interleave ? "interleaved.java" : "control.java"), text);
				boolean failed = text.contains("Method arg registers not loaded:")
						|| text.contains("Expected class to be processed at this point,");
				System.out.println("interleave=" + interleave + ", dependency state error=" + failed);
				if (failed != interleave) {
					throw new AssertionError("Unexpected reproduction outcome");
				}
			}
		}
	}
}
