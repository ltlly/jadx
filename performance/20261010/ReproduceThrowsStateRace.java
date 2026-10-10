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
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.visitors.AbstractVisitor;
import jadx.core.dex.visitors.blocks.BlockSplitter;
import jadx.core.utils.exceptions.JadxRuntimeException;

/** Replays recursive throws analysis while another class is between real passes. */
public class ReproduceThrowsStateRace {

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
			ClassNode target = root.resolveClass("org.telegram.messenger.AndroidUtilities");
			ClassNode consumer = root.resolveClass("ah.d");
			ProcessClass process = root.getProcessClasses();
			CountDownLatch paused = new CountDownLatch(1);
			CountDownLatch resume = new CountDownLatch(1);
			ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "throws-state-window"));
			Future<?> processing = null;
			try {
				if (interleave) {
					int index = 0;
					while (!(root.getPasses().get(index) instanceof BlockSplitter)) {
						index++;
					}
					root.getPasses().add(index + 1, new AbstractVisitor() {
						@Override
						public boolean visit(ClassNode cls) {
							if (cls == target && Thread.currentThread().getName().equals("throws-state-window")) {
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
					processing = executor.submit(() -> process.forceProcess(target));
					if (!paused.await(30, TimeUnit.SECONDS)) {
						throw new AssertionError("Target did not enter the controlled state window");
					}
				} else {
					process.forceProcess(target);
				}
				process.forceProcess(consumer);
			} finally {
				resume.countDown();
				try {
					if (processing != null) {
						processing.get();
					}
				} finally {
					executor.shutdownNow();
				}
			}
			String consumerCode = process.forceGenerateCode(consumer).getCodeStr();
			Files.writeString(destination.resolveSibling(destination.getFileName() + ".consumer.java"), consumerCode);
			String code = process.forceGenerateCode(target).getCodeStr();
			Files.writeString(destination, code);
			boolean expanded = code.contains("doSafe(Utilities.Callback0Return<Boolean> callback0Return) throws "
					+ "ExecutionException, InterruptedException, TimeoutException");
			System.out.println("interleave=" + interleave + ", expandedThrows=" + expanded);
			if (expanded != interleave) {
				throw new AssertionError("Unexpected controlled outcome");
			}
		}
	}
}
