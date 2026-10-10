package jadx.core.dex.nodes.deps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.plugins.pass.JadxPass;
import jadx.api.plugins.pass.types.JadxDependencySummaryPass;
import jadx.api.impl.passes.DecompilePassWrapper;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.ProcessState;
import jadx.core.dex.nodes.RootNode;
import jadx.core.utils.exceptions.JadxRuntimeException;

import static jadx.core.dex.nodes.ProcessState.GENERATED_AND_UNLOADED;

/**
 * Per-Root owner of dependency analysis summaries: save epoch handling, kill
 * switch, byte budget, prepare/certify/recover orchestration and miss-reason
 * counters.
 *
 * <p>All summary state transitions happen under the existing
 * {@code synchronized(cls.getClassInfo())} monitor discipline; this class adds
 * no locks of its own. Enabled only during a full source save epoch.
 *
 * <p>Kill switch: {@code -Djadx.deps.summary=false} keeps the full legacy
 * re-process behavior. Missing property defaults to {@code false} (development
 * default); an invalid value is treated as {@code false} with one log record.
 */
public final class DependencySummaryManager {
	private static final Logger LOG = LoggerFactory.getLogger(DependencySummaryManager.class);

	public static final String ENABLE_PROPERTY = "jadx.deps.summary";
	public static final String STATS_PROPERTY = "jadx.deps.summary.stats";
	public static final String STRICT_PROPERTY = "jadx.deps.summary.strict";

	/** Retained summary payload budget per Root. */
	private static final long RETAINED_BUDGET_BYTES = 128L * 1024 * 1024;
	/** In-flight scratch budget. */
	private static final long SCRATCH_BUDGET_BYTES = 16L * 1024 * 1024;
	/** Single summary cap. */
	private static final long SINGLE_SUMMARY_CAP_BYTES = 4L * 1024 * 1024;
	/** Conservative flat estimate for the compact v1 payload. */
	private static final int V1_SUMMARY_ESTIMATE_BYTES = 256;

	private final RootNode root;
	private final boolean enabled;
	private final boolean statsEnabled;
	private final boolean strictChecks;

	private final DependencySummaryReplay replay;

	private final AtomicInteger epochCounter = new AtomicInteger(0);
	private volatile long currentEpochId = -1;
	private volatile boolean epochActive = false;
	/** Global disable reason for the current Root (e.g. unknown custom pass). */
	private volatile @Nullable String globalDisableReason;

	// coarse budget accounting
	private final AtomicLong retainedBytes = new AtomicLong(0);
	private final AtomicLong scratchBytes = new AtomicLong(0);
	/** test-only retained budget override */
	private volatile long retainedBudgetOverride = -1;

	// miss/coverage counters
	private final LongAdder preparedCount = new LongAdder();
	private final LongAdder summaryRecoveries = new LongAdder();
	private final LongAdder fallbackReentries = new LongAdder();
	private final LongAdder legacyFallbackRecoveryNanos = new LongAdder();
	private final LongAdder summaryRecoveryNanos = new LongAdder();
	private final Map<String, LongAdder> missReasons = new ConcurrentHashMap<>();
	// stage-2a stats: first-reentry multi-reason bitmasks and per-reason legacy time
	private final Map<String, LongAdder> missBitmasks = new ConcurrentHashMap<>();
	private final Map<String, LongAdder> missReasonNanos = new ConcurrentHashMap<>();
	private final LongAdder verifyNanos = new LongAdder();
	private final LongAdder replayNanos = new LongAdder();
	private final LongAdder annotationRecoveries = new LongAdder();
	/** miss reason of the re-entry currently falling back on this thread */
	private final ThreadLocal<String> pendingMissReason = new ThreadLocal<>();
	/** package-private test observation hook: invoked after each recovery attempt. */
	volatile @Nullable Runnable recoveryObserver;

	public DependencySummaryManager(RootNode root) {
		this.root = root;
		this.enabled = parseEnableProperty();
		this.statsEnabled = Boolean.getBoolean(STATS_PROPERTY);
		this.strictChecks = Boolean.parseBoolean(System.getProperty(STRICT_PROPERTY, "true"));
		this.replay = new DependencySummaryReplay(root);
	}

	private static boolean parseEnableProperty() {
		String value = System.getProperty(ENABLE_PROPERTY);
		if (value == null) {
			return false;
		}
		if (value.equalsIgnoreCase("true")) {
			return true;
		}
		if (!value.equalsIgnoreCase("false")) {
			LOG.warn("Invalid value for -D{}='{}', treat as false", ENABLE_PROPERTY, value);
		}
		return false;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public boolean isEpochActive() {
		return epochActive;
	}

	public static boolean isStrictChecksEnabled() {
		return Boolean.parseBoolean(System.getProperty(STRICT_PROPERTY, "true"));
	}

	/**
	 * Begin a save epoch: validates the exact merged pass list. Called from the
	 * source save task construction, before decompile tasks run.
	 */
	public void beginEpoch() {
		if (!enabled || epochActive) {
			return;
		}
		String passListProblem = DependencyRecoveryCertificate.validatePassList(root);
		if (passListProblem != null) {
			globalDisableReason = passListProblem;
			LOG.debug("Dependency summaries disabled for this Root: {}", passListProblem);
			return;
		}
		if (hasCustomCodeData()) {
			globalDisableReason = "CUSTOM_CODE_DATA";
			return;
		}
		globalDisableReason = null;
		currentEpochId = epochCounter.incrementAndGet();
		epochActive = true;
	}

	/**
	 * End the save epoch: drop transient builder/scratch state and PREPARED
	 * summaries. Exported RECOVERED node state stays live as legacy would keep it.
	 */
	public void endEpoch() {
		if (!enabled || !epochActive) {
			return;
		}
		epochActive = false;
		if (statsEnabled) {
			printStatsLine();
		}
	}

	/** Invalidate all summaries (Root reset/restart/reload/mode change). */
	public void invalidateAll() {
		epochActive = false;
		currentEpochId = -1;
		retainedBytes.set(0);
		scratchBytes.set(0);
		for (ClassNode cls : root.getClasses()) {
			cls.clearDependencySummaryTree();
		}
	}

	private boolean hasCustomCodeData() {
		jadx.api.data.ICodeData codeData = root.getArgs().getCodeData();
		return codeData != null && !codeData.isEmpty();
	}

	/**
	 * Prepare a summary for the class right after its own codegen, before the
	 * ordinary generated unload clears flags and non-pinned attrs. Must be called
	 * under the class monitor.
	 */
	public void prepareAfterCodegen(ClassNode topCls) {
		if (!enabled || !epochActive || globalDisableReason != null) {
			return;
		}
		if (topCls.isInner() || topCls.contains(AFlag.DONT_UNLOAD_CLASS)) {
			return;
		}
		String missReason = certify(topCls);
		if (missReason != null) {
			miss(missReason);
			return;
		}
		if (!reserveBudget(V1_SUMMARY_ESTIMATE_BYTES)) {
			miss("BUDGET_EXCEEDED");
			return;
		}
		DependencyAnalysisSummary summary = new DependencyAnalysisSummary(
				currentEpochId, topCls.getRawName(), root.getProcessClasses().getPasses().hashCode(),
				V1_SUMMARY_ESTIMATE_BYTES);
		topCls.setDependencySummary(summary);
		preparedCount.increment();
	}

	private @Nullable String certify(ClassNode topCls) {
		String missReason = DependencyRecoveryCertificate.verifySubtree(topCls);
		if (missReason != null) {
			return missReason;
		}
		// custom decompile passes with the no-op capability must confirm the subtree
		for (IDepCheck check : getCapabilityChecks()) {
			String capabilityMiss = check.test(topCls);
			if (capabilityMiss != null) {
				return capabilityMiss;
			}
		}
		return null;
	}

	private interface IDepCheck {
		@Nullable
		String test(ClassNode topCls);
	}

	private final List<IDepCheck> capabilityChecks = new ArrayList<>();

	private List<IDepCheck> getCapabilityChecks() {
		if (capabilityChecks.isEmpty()) {
			for (jadx.core.dex.visitors.IDexTreeVisitor visitor : root.getProcessClasses().getPasses()) {
				if (visitor instanceof DecompilePassWrapper) {
					JadxPass pass = ((DecompilePassWrapper) visitor).getPass();
					if (pass instanceof JadxDependencySummaryPass) {
						capabilityChecks.add(topCls -> {
							boolean noOp;
							try {
								noOp = ((JadxDependencySummaryPass) pass).isSummaryNoOpFor(topCls);
							} catch (Exception e) {
								// fail closed on capability errors
								return "CAPABILITY_ERROR";
							}
							return noOp ? null : "PLUGIN_PASS_NOT_NO_OP";
						});
					}
				}
			}
		}
		return capabilityChecks;
	}

	/**
	 * Try a certified recovery at a metadata dependency re-entry instead of the
	 * full legacy re-process. Must be called under the class monitor with the
	 * class in {@link ProcessState#GENERATED_AND_UNLOADED}. On any unsupported
	 * state or verification failure the legacy path proceeds untouched.
	 *
	 * @return {@code true} if the summary recovery was executed and published
	 */
	public boolean tryRecover(ClassNode topCls) {
		if (!enabled || !epochActive || globalDisableReason != null) {
			return false;
		}
		DependencyAnalysisSummary summary = topCls.getDependencySummary();
		if (summary == null || !summary.isPrepared()) {
			return false;
		}
		if (summary.getEpochId() != currentEpochId) {
			summary.setInvalid();
			releaseBudget(summary.getEstimatedBytes());
			recordReentryMiss(topCls, "EPOCH_MISMATCH");
			return false;
		}
		if (topCls.contains(AFlag.CLASS_DEEP_RELOAD) || topCls.contains(AFlag.CLASS_UNLOADED)) {
			summary.setInvalid();
			releaseBudget(summary.getEstimatedBytes());
			recordReentryMiss(topCls, "RELOAD_PENDING");
			return false;
		}
		// verification happens strictly before any residual mutation
		long verifyStart = System.nanoTime();
		String missReason = DependencyRecoveryCertificate.verifySubtree(topCls);
		if (missReason == null) {
			missReason = DependencyRecoveryCertificate.verifyThrowsWitness(topCls);
		}
		if (missReason == null) {
			for (IDepCheck check : getCapabilityChecks()) {
				missReason = check.test(topCls);
				if (missReason != null) {
					break;
				}
			}
		}
		verifyNanos.add(System.nanoTime() - verifyStart);
		if (missReason != null) {
			summary.setInvalid();
			releaseBudget(summary.getEstimatedBytes());
			recordReentryMiss(topCls, missReason);
			return false;
		}
		// recheck state under the monitor: another thread may have recovered already
		if (topCls.getState() != GENERATED_AND_UNLOADED) {
			return false;
		}
		long start = System.nanoTime();
		replay.execute(topCls, summary);
		replayNanos.add(System.nanoTime() - start);
		if (statsEnabled && DependencyRecoveryCertificate.hasPinnedAnnotations(topCls)) {
			annotationRecoveries.increment();
		}
		topCls.setLoadStage(jadx.core.dex.nodes.LoadStage.PROCESS_STAGE);
		summary.setRecovered();
		// publish state last so other threads observe recovered state via HB
		topCls.setState(ProcessState.PROCESS_COMPLETE);
		topCls.setSummaryRecoveredResidency(true);
		summaryRecoveryNanos.add(System.nanoTime() - start);
		summaryRecoveries.increment();
		runRecoveryObserver();
		return true;
	}

	public void recordLegacyRecovery(long nanos) {
		legacyFallbackRecoveryNanos.add(nanos);
		// attribute the fallback time to the miss reason recorded by the failed
		// recovery attempt on this thread (nested recoveries may drain earlier)
		String reason = pendingMissReason.get();
		if (reason != null) {
			pendingMissReason.remove();
			missReasonNanos.computeIfAbsent(reason, r -> new LongAdder()).add(nanos);
		}
	}

	/**
	 * Record a re-entry miss: primary first-reason counter (v1 semantics), plus
	 * the stage-2a multi-reason bitmask and the pending reason for per-reason
	 * legacy time attribution.
	 */
	private void recordReentryMiss(ClassNode topCls, String reason) {
		miss(reason);
		pendingMissReason.set(reason);
		if (statsEnabled) {
			List<String> all = DependencyRecoveryCertificate.collectAllMissReasons(topCls);
			String mask = all.isEmpty() ? reason : String.join("+", all);
			missBitmasks.computeIfAbsent(mask, m -> new LongAdder()).increment();
		}
	}

	/** Public counter for re-entries which fell back to the legacy recovery. */
	public void recordFallbackReentry() {
		fallbackReentries.increment();
	}

	public static void checkUnexpectedBodyLoad(MethodNode mth) {
		// strict checks are property-driven so they work even without a manager reference
		if (!isStrictChecksEnabled() || !parseEnableProperty()) {
			return;
		}
		ClassNode topCls = mth.getTopParentClass();
		if (topCls.isSummaryRecoveredResidency()) {
			throw new JadxRuntimeException(
					"Unexpected body load for summary-recovered class: " + topCls + ", method: " + mth);
		}
	}

	private boolean reserveBudget(int bytes) {
		if (bytes > SINGLE_SUMMARY_CAP_BYTES) {
			return false;
		}
		long budget = retainedBudgetOverride >= 0 ? retainedBudgetOverride : RETAINED_BUDGET_BYTES;
		if (retainedBytes.addAndGet(bytes) > budget
				|| scratchBytes.get() > SCRATCH_BUDGET_BYTES) {
			retainedBytes.addAndGet(-bytes);
			return false;
		}
		return true;
	}

	private void releaseBudget(int bytes) {
		retainedBytes.addAndGet(-bytes);
	}

	private void miss(String reason) {
		missReasons.computeIfAbsent(reason, r -> new LongAdder()).increment();
	}

	String formatStatsLine() {
		long recoveries = summaryRecoveries.sum();
		long fallbacks = fallbackReentries.sum();
		long legacyNanos = legacyFallbackRecoveryNanos.sum();
		long summaryN = summaryRecoveryNanos.sum();
		return "[jadx-deps-summary] prepared=" + preparedCount.sum()
				+ " summary-recoveries=" + recoveries
				+ " fallback-reentries=" + fallbacks
				+ " reentries=" + (recoveries + fallbacks)
				+ " summary-time-ms=" + summaryN / 1_000_000
				+ " legacy-time-ms=" + legacyNanos / 1_000_000
				+ " verify-time-ms=" + verifyNanos.sum() / 1_000_000
				+ " replay-time-ms=" + replayNanos.sum() / 1_000_000
				+ " annotation-recoveries=" + annotationRecoveries.sum()
				+ " misses=[" + formatMap(missReasons) + ']'
				+ " miss-bitmasks=[" + formatMap(missBitmasks) + ']'
				+ " miss-time-ms=[" + formatMap(missReasonNanos) + ']';
	}

	private static String formatMap(Map<String, LongAdder> map) {
		List<String> entries = new ArrayList<>(map.size());
		map.forEach((k, v) -> entries.add(k + ":" + v.sum()));
		entries.sort(String::compareTo);
		return String.join(",", entries);
	}

	private void printStatsLine() {
		System.err.println(formatStatsLine());
	}

	// test/diagnostic accessors (package-private or read-only)
	long getEpochId() {
		return currentEpochId;
	}

	long getPreparedCount() {
		return preparedCount.sum();
	}

	long getSummaryRecoveries() {
		return summaryRecoveries.sum();
	}

	long getFallbackReentries() {
		return fallbackReentries.sum();
	}

	long getSummaryRecoveryNanos() {
		return summaryRecoveryNanos.sum();
	}

	long getLegacyRecoveryNanos() {
		return legacyFallbackRecoveryNanos.sum();
	}

	Map<String, LongAdder> getMissReasons() {
		return missReasons;
	}

	Map<String, LongAdder> getMissBitmasks() {
		return missBitmasks;
	}

	Map<String, LongAdder> getMissReasonNanos() {
		return missReasonNanos;
	}

	long getVerifyNanos() {
		return verifyNanos.sum();
	}

	long getReplayNanos() {
		return replayNanos.sum();
	}

	long getAnnotationRecoveries() {
		return annotationRecoveries.sum();
	}

	void setRecoveryObserver(@Nullable Runnable observer) {
		this.recoveryObserver = observer;
	}

	void setRetainedBudgetForTest(long bytes) {
		this.retainedBudgetOverride = bytes;
	}

	void runRecoveryObserver() {
		Runnable observer = recoveryObserver;
		if (observer != null) {
			observer.run();
		}
	}

}
