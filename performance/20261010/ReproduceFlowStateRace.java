import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.ProcessClass;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.visitors.AbstractVisitor;
import jadx.core.utils.exceptions.JadxRuntimeException;

/** Pause after real dependency passes, before its PROCESS_COMPLETE publication. */
public class ReproduceFlowStateRace {
	public static void main(String[] args) throws Exception {
		boolean interleave = Boolean.parseBoolean(args[2]);
		Path destination = Path.of(args[1]);
		Files.createDirectories(destination.getParent());
		JadxArgs options = new JadxArgs();
		options.setInputFiles(List.of(new File(args[0])));
		options.setSkipResources(true);
		options.setThreadsCount(8);
		try (JadxDecompiler jadx = new JadxDecompiler(options)) {
			jadx.load();
			RootNode root = jadx.getRoot();
			String prefix = "kotlinx.coroutines.flow.FlowKt__ZipKt";
			ClassNode consumer = root.resolveClass(prefix + "$combineTransform$$inlined$combineTransformUnsafe$FlowKt__ZipKt$5");
			ClassNode dependency = root.resolveClass(prefix + "$nullArrayFactory$1");
			ProcessClass process = root.getProcessClasses();
			for (ClassNode dep : consumer.getDependencies()) {
				process.forceProcess(dep);
			}
			if (!consumer.getCodegenDeps().isEmpty()) {
				process.forceProcess(consumer);
				for (ClassNode dep : consumer.getCodegenDeps()) {
					process.forceProcess(dep);
				}
			}
			process.generateCode(root.resolveClass(prefix));
			System.out.println("before: state=" + dependency.getState() + ", top=" + dependency.getTopParentClass()
					+ ", inlineInstance=" + dependency.searchFieldByName("INSTANCE").contains(AFlag.INLINE_INSTANCE_FIELD));
			CountDownLatch paused = new CountDownLatch(1);
			CountDownLatch resume = new CountDownLatch(1);
			ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "dependency-state-window"));
			Future<?> reprocessing = null;
			String code;
			try {
				if (interleave) {
					root.getPasses().add(new AbstractVisitor() {
						@Override
						public boolean visit(ClassNode cls) {
							if (cls == dependency && Thread.currentThread().getName().equals("dependency-state-window")) {
								paused.countDown();
								try {
									resume.await();
								} catch (InterruptedException e) {
									Thread.currentThread().interrupt();
									throw new JadxRuntimeException("Control pause interrupted", e);
								}
							}
							return false;
						}
					});
					reprocessing = executor.submit(() -> {
						dependency.reloadCode();
						// DONT_GENERATE can return before consuming the reload flag.
						process.forceGenerateCode(dependency);
					});
					if (!paused.await(30, TimeUnit.SECONDS)) {
						throw new AssertionError("Dependency did not enter the controlled state window");
					}
					System.out.println("state=" + dependency.getState() + ", anonymous=" + dependency.contains(AType.ANONYMOUS_CLASS)
							+ ", inlineInstance=" + dependency.searchFieldByName("INSTANCE").contains(AFlag.INLINE_INSTANCE_FIELD));
				}
				code = process.forceGenerateCode(consumer).getCodeStr();
			} finally {
				resume.countDown();
				try {
					if (reprocessing != null) {
						reprocessing.get();
					}
				} finally {
					executor.shutdownNow();
				}
			}
			Files.writeString(destination, code);
			boolean failed = code.contains("Expected class to be processed at this point, class: " + dependency.getRawName());
			System.out.println("interleave=" + interleave + ", expectedClassError=" + failed);
			if (failed != interleave) {
				throw new AssertionError("Unexpected controlled outcome");
			}
		}
	}
}
