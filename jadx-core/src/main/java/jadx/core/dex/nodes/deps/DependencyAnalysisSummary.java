package jadx.core.dex.nodes.deps;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable payload attached to a top level {@link jadx.core.dex.nodes.ClassNode}
 * after its own codegen round, describing how the class can be recovered at a
 * later metadata dependency request without a full legacy re-process.
 *
 * <p>The v1 payload is deliberately compact: it stores epoch/pass identity and
 * demand classification only. All structural witnesses are re-verified live
 * from node state at recovery time, so no IR/SSA/flag copies are retained.
 */
public final class DependencyAnalysisSummary {

	public enum Lifecycle {
		/** Prepared after first own processing, before generated unload. */
		PREPARED,
		/** Recovery executed at a metadata dependency re-entry. */
		RECOVERED,
		/** Certificate invalidated, payload is dead. */
		INVALID
	}

	private final long epochId;
	private final String topClsRawName;
	private final int passListFingerprint;
	private final int estimatedBytes;
	private volatile Lifecycle lifecycle = Lifecycle.PREPARED;

	public DependencyAnalysisSummary(long epochId, String topClsRawName, int passListFingerprint, int estimatedBytes) {
		this.epochId = epochId;
		this.topClsRawName = topClsRawName;
		this.passListFingerprint = passListFingerprint;
		this.estimatedBytes = estimatedBytes;
	}

	public long getEpochId() {
		return epochId;
	}

	public String getTopClsRawName() {
		return topClsRawName;
	}

	public int getPassListFingerprint() {
		return passListFingerprint;
	}

	public int getEstimatedBytes() {
		return estimatedBytes;
	}

	public Lifecycle getLifecycle() {
		return lifecycle;
	}

	public boolean isPrepared() {
		return lifecycle == Lifecycle.PREPARED;
	}

	public boolean isRecovered() {
		return lifecycle == Lifecycle.RECOVERED;
	}

	public void setRecovered() {
		this.lifecycle = Lifecycle.RECOVERED;
	}

	public void setInvalid() {
		this.lifecycle = Lifecycle.INVALID;
	}

	@Override
	public String toString() {
		return "DependencySummary{" + topClsRawName + ", epoch=" + epochId + ", state=" + lifecycle + '}';
	}

	public static @Nullable String lifecycleName(@Nullable DependencyAnalysisSummary summary) {
		return summary == null ? null : summary.getLifecycle().name();
	}
}
