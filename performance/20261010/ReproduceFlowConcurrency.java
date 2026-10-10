import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

/** Ordinary eight-thread class generation; no bytecode or visitor changes. */
public class ReproduceFlowConcurrency {
	public static void main(String[] args) throws Exception {
		String target = "kotlinx.coroutines.flow.FlowKt__ZipKt$combineTransform$$inlined$combineTransformUnsafe$FlowKt__ZipKt$5";
		Path destination = Path.of(args[1]);
		Files.createDirectories(destination);
		JadxArgs options = new JadxArgs();
		options.setInputFiles(List.of(new File(args[0])));
		options.setSkipResources(true);
		options.setThreadsCount(8);
		try (JadxDecompiler jadx = new JadxDecompiler(options)) {
			jadx.load();
			List<JavaClass> classes = new ArrayList<>();
			for (JavaClass cls : jadx.getClasses()) {
				if (cls.getRawName().startsWith("kotlinx.coroutines.flow.")) {
					classes.add(cls);
				}
			}
			Collections.shuffle(classes, new Random(Long.parseLong(args[2])));
			ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();
			List<Callable<Void>> tasks = new ArrayList<>();
			for (JavaClass cls : classes) {
				tasks.add(() -> {
					results.put(cls.getRawName(), cls.getCode());
					return null;
				});
			}
			ExecutorService executor = Executors.newFixedThreadPool(8);
			try {
				for (Future<Void> task : executor.invokeAll(tasks)) {
					task.get();
				}
			} finally {
				executor.shutdownNow();
			}
			String code = results.get(target);
			if (code == null) {
				throw new AssertionError("Target missing from " + classes.size() + " selected classes");
			}
			Files.writeString(destination.resolve("target.java"), code);
			boolean failed = code.contains("JADX ERROR:");
			Files.writeString(destination.resolve("result.txt"), "seed=" + args[2] + ", classes=" + classes.size()
					+ ", targetFailed=" + failed + "\n");
			System.out.println("TARGET_FAILED=" + failed);
		}
	}
}
