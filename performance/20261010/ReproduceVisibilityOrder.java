import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.dex.nodes.ClassNode;

/** Compare two ordinary class generation orders using unmodified baseline JARs. */
public class ReproduceVisibilityOrder {

	public static void main(String[] args) throws Exception {
		Path destination = Path.of(args[1]);
		Files.createDirectories(destination);
		for (boolean targetFirst : new boolean[] { true, false }) {
			JadxArgs options = new JadxArgs();
			options.setInputFiles(List.of(new File(args[0])));
			options.setSkipResources(true);
			options.setThreadsCount(8);
			try (JadxDecompiler jadx = new JadxDecompiler(options)) {
				jadx.load();
				ClassNode target = jadx.getRoot().resolveClass("androidx.navigationevent.NavigationEventInput");
				ClassNode caller = jadx.getRoot().resolveClass("androidx.navigationevent.OnBackInvokedInput");
				if (target == null || caller == null) {
					throw new AssertionError("Expected DuckDuckGo classes missing");
				}
				String code;
				if (targetFirst) {
					code = target.getCode().getCodeStr();
					caller.getCode();
				} else {
					caller.getCode();
					code = target.getCode().getCodeStr();
				}
				Files.writeString(destination.resolve(targetFirst ? "target-first.java" : "caller-first.java"), code);
				boolean isPublic = code.contains("public final void dispatchOnBackCompleted()");
				boolean isProtected = code.contains("protected final void dispatchOnBackCompleted()");
				System.out.println("targetFirst=" + targetFirst + ", public=" + isPublic + ", protected=" + isProtected);
				if (isPublic == targetFirst || isProtected != targetFirst) {
					throw new AssertionError("Unexpected generation-order result");
				}
			}
		}
	}
}
