package jadx.api.plugins.pass.types;

import jadx.core.dex.nodes.ClassNode;

/**
 * Optional capability for a {@link JadxDecompilePass} which can prove it performs
 * no observable action for a given top class subtree in any decompilation round.
 *
 * <p>Intended to allow dependency summary recovery for subtrees which would
 * otherwise be blocked by an unknown custom pass. Implementations must fail
 * closed: return {@code false} when in doubt.
 */
public interface JadxDependencySummaryPass extends JadxDecompilePass {

	/**
	 * Return {@code true} only if this pass is a guaranteed no-op for the whole
	 * top class subtree in every processing round (i.e. the pass result cannot
	 * differ between a full legacy recovery and a summary-only recovery).
	 *
	 * @param topCls top level class (subtree owner)
	 */
	boolean isSummaryNoOpFor(ClassNode topCls);
}
