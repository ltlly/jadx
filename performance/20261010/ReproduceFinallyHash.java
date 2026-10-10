import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.trycatch.TryEdgeType;

/** Observe baseline finally output under different legal enum identity hashes. */
public class ReproduceFinallyHash {

	public static void main(String[] args) throws Exception {
		int priming = Integer.parseInt(args[2]);
		int checksum = 0;
		for (int i = 0; i < priming; i++) {
			checksum ^= System.identityHashCode(new Object());
		}
		System.out.println("priming=" + priming + ", checksum=" + checksum);
		for (TryEdgeType type : TryEdgeType.values()) {
			System.out.println(type + " hash=" + type.hashCode());
		}
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
			String code = target.getCode().getCodeStr();
			Path destination = Path.of(args[3]);
			Files.createDirectories(destination.getParent());
			Files.writeString(destination, code);
			System.out.println("omitted=" + code.contains("Method not decompiled:")
					+ ", missingBottom=" + code.contains("Bottom block not found for handler:"));
		}
	}
}
