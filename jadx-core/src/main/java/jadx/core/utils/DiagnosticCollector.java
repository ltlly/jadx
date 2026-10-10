package jadx.core.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import com.google.gson.stream.JsonWriter;

import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;

/**
 * Local worker-pool diagnostics, active only with -Djadx.diagnostic=true.
 * All hot-path entry points are guarded by a static final boolean at call sites;
 * nothing here is touched when the property is off. Output is a single JSON file
 * written at JVM exit to the path from -Djadx.diagnostic.output.
 * Not eligible for timed A/B comparisons (perturbs interleaving/GC/locks).
 */
public final class DiagnosticCollector {
	/** Static final gate, captured at JVM start. Never toggled at runtime. */
	public static final boolean ENABLED = Boolean.getBoolean("jadx.diagnostic");

	private static final String SOURCE_COMMIT = "6453903f3858ddef1112ac09ad9df816cfcb801c";
	private static final long TICK_INTERVAL_NS = 1_000_000_000L;

	private static final int REASONS = Reason.COUNT;
	// per-reason metric slots
	private static final int M_COUNT = 0;
	private static final int M_FAST_RETURNS = 1;
	private static final int M_ANALYSIS_STARTED = 2;
	private static final int M_REPEAT_ANALYSIS = 3;
	private static final int M_WAIT_NS = 4;
	private static final int M_HELD_INCL_NS = 5;
	private static final int M_VISITORS_NS = 6;
	private static final int M_REPEAT_VISITORS_NS = 7;
	private static final int M_RECOVERY_EXCL_NS = 8;
	private static final int M_CODEGEN_ONLY_NS = 9;
	private static final int M_METRICS = 10;

	// stage phase kinds
	private static final int PHASE_RESOURCES = 1;
	private static final int PHASE_SOURCES = 2;
	private static final int PHASE_SERIAL = 3;
	private static final int PHASE_PRE = 4;

	private enum Reason {
		DEPENDENCY,
		CODEGEN_SELF_PREPARE,
		CODEGEN_DEPENDENCY,
		SAVE_SELF,
		GENERATE_SELF_OTHER,
		DONT_GENERATE_PREPARE,
		FORCE_PROCESS,
		FORCE_CODEGEN,
		UNKNOWN;

		static final int COUNT = values().length;

		static int indexOf(String name) {
			for (Reason r : values()) {
				if (r.name().equals(name)) {
					return r.ordinal();
				}
			}
			return UNKNOWN.ordinal();
		}
	}

	/** Opaque per-call frame for process() instrumentation. */
	public static final class Frame {
		Frame prev;
		long startNs;
		long acquiredNs;
		long waitNs;
		long childCallNs;
		long visitorsStartNs = -1;
		long visitorsNs;
		long codegenStartNs = -1;
		long codegenNs;
		ClassStats cs;
		int reasonIdx;
		long batchId = -1;
		int analysisOrdinal;
		boolean codegen;
	}

	private static final class IterFrame {
		long startNs;
		int batchId;
		int slot;
		long waitAccum;
		long recoveryAccum;
	}

	private static final class ClassKey {
		final ClassNode node;
		final int hash;

		ClassKey(ClassNode node) {
			this.node = node;
			this.hash = System.identityHashCode(node);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof ClassKey && ((ClassKey) o).node == node;
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	private static final class ClassStats {
		final int id;
		final String rawName;
		final String topParentName;
		final boolean topLevel;
		final AtomicLong invocations = new AtomicLong();
		final AtomicLong analysisStarted = new AtomicLong();
		final AtomicLong analysisCompleted = new AtomicLong();
		final AtomicLong fastReturns = new AtomicLong();
		final AtomicLong monitorAcquisitions = new AtomicLong();
		final AtomicLong waitNs = new AtomicLong();
		final AtomicLong maxWaitNs = new AtomicLong();
		final AtomicLong heldInclusiveNs = new AtomicLong();
		final AtomicLong heldExclusiveNs = new AtomicLong();
		final AtomicLong repeatHeldInclusiveNs = new AtomicLong();
		final AtomicLong visitorsNs = new AtomicLong();
		final AtomicLong repeatVisitorsNs = new AtomicLong();
		final AtomicLong codegenNs = new AtomicLong();
		final AtomicLong repeatRecoveryNs = new AtomicLong();
		final AtomicInteger firstReason = new AtomicInteger(-1);
		final AtomicInteger lastReason = new AtomicInteger(-1);

		ClassStats(int id, String rawName, String topParentName, boolean topLevel) {
			this.id = id;
			this.rawName = rawName;
			this.topParentName = topParentName;
			this.topLevel = topLevel;
		}
	}

	private static final class BatchStats {
		final int id;
		final int size;
		volatile long startRelNs = -1;
		volatile long endRelNs = -1;
		volatile long elapsedNs;
		volatile long[] iterationServiceNs;
		final AtomicLong classServiceNs = new AtomicLong();
		final AtomicLong monitorWaitNs = new AtomicLong();
		final AtomicLong repeatRecoveryNs = new AtomicLong();
		final AtomicInteger iterationsDone = new AtomicInteger();
		final AtomicInteger errors = new AtomicInteger();

		BatchStats(int id, int size) {
			this.id = id;
			this.size = size;
		}
	}

	private static final class StageStats {
		final int id;
		final String type;
		final int phaseKind;
		final int tasksCount;
		volatile long startRelNs = -1;
		volatile long endRelNs = -1;
		volatile boolean drained;
		final AtomicLong busyWorkerNs = new AtomicLong();
		final AtomicInteger peakBusy = new AtomicInteger();
		final AtomicLong tasksStarted = new AtomicLong();
		final AtomicLong tasksFinished = new AtomicLong();
		final AtomicLong tasksSkipped = new AtomicLong();
		int poolThreads;

		StageStats(int id, String type, int phaseKind, int tasksCount) {
			this.id = id;
			this.type = type;
			this.phaseKind = phaseKind;
			this.tasksCount = tasksCount;
		}
	}

	private static final class Tick {
		final long relNs;
		final long dtNs;
		final String phase;
		final int busySources;
		final int busyResources;
		final int busyPre;
		final int busySerial;
		final int queueSize;
		final long cpuNs;
		final long cpuDeltaNs;

		Tick(long relNs, long dtNs, String phase, int bs, int br, int bp, int bseq, int queue, long cpu, long cpuDelta) {
			this.relNs = relNs;
			this.dtNs = dtNs;
			this.phase = phase;
			this.busySources = bs;
			this.busyResources = br;
			this.busyPre = bp;
			this.busySerial = bseq;
			this.queueSize = queue;
			this.cpuNs = cpu;
			this.cpuDeltaNs = cpuDelta;
		}
	}

	private static final class Holder {
		static final DiagnosticCollector INSTANCE = new DiagnosticCollector();
	}

	private static DiagnosticCollector collector() {
		return Holder.INSTANCE;
	}

	// instance state
	private final ConcurrentHashMap<ClassKey, ClassStats> classes = new ConcurrentHashMap<>(16384);
	private final List<BatchStats> batches = new CopyOnWriteArrayList<>();
	private final List<StageStats> stages = new CopyOnWriteArrayList<>();
	private final List<Tick> timeline = new CopyOnWriteArrayList<>();
	private final AtomicLongArray reasonMetrics = new AtomicLongArray(REASONS * M_METRICS);
	private final AtomicLong invocations = new AtomicLong();
	private final AtomicLong fastReturns = new AtomicLong();
	private final AtomicLong analysisStarted = new AtomicLong();
	private final AtomicLong analysisCompleted = new AtomicLong();
	private final AtomicLong monitorAcquisitions = new AtomicLong();
	private final AtomicLong monitorWaitTotalNs = new AtomicLong();
	private final AtomicLong monitorWaitMaxNs = new AtomicLong();
	private final AtomicLong waitOver100us = new AtomicLong();
	private final AtomicLong waitOver1ms = new AtomicLong();
	private final AtomicLongArray waitLog2Buckets = new AtomicLongArray(48);
	private final AtomicLong recordingErrors = new AtomicLong();
	private final AtomicInteger classSeq = new AtomicInteger();
	private final AtomicInteger batchSeq = new AtomicInteger();
	private final AtomicInteger planBatchCursor = new AtomicInteger();
	private final AtomicInteger workerSeq = new AtomicInteger();
	private final AtomicInteger busySources = new AtomicInteger();
	private final AtomicInteger busyResources = new AtomicInteger();
	private final AtomicInteger busyPre = new AtomicInteger();
	private final AtomicInteger busySerial = new AtomicInteger();
	private final Map<String, long[]> phaseWalls = new HashMap<>();

	private final ThreadLocal<Frame> frameStack = new ThreadLocal<>();
	private final ThreadLocal<String> requestReasonToken = new ThreadLocal<>();
	private final ThreadLocal<Integer> originBatch = new ThreadLocal<>();
	private final ThreadLocal<IterFrame> currentIteration = new ThreadLocal<>();
	private final ThreadLocal<Integer> workerId = new ThreadLocal<>();
	private final ThreadLocal<Integer> pendingStagePhaseKind = new ThreadLocal<>();

	private volatile String currentPhase = "INIT";
	private volatile int currentStageKind = PHASE_SERIAL;
	private volatile ThreadPoolExecutor savePool;
	private volatile boolean runStarted;
	private volatile boolean runClosed;
	private volatile long runStartNano;
	private volatile long phaseStartNano;
	private volatile long runClosedRelNs = -1;
	private volatile long processCpuStartNs = -1;
	private volatile long processCpuEndNs = -1;
	private int configuredThreads = -1;
	private int topLevelRegistered;
	private int loadedAllRegistered;
	private final String outputPath;

	private DiagnosticCollector() {
		String path = System.getProperty("jadx.diagnostic.output");
		if (path == null || path.trim().isEmpty()) {
			this.outputPath = null;
		} else {
			this.outputPath = path.trim();
		}
		if (this.outputPath != null) {
			Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownWrite, "jadx-diagnostic-shutdown"));
		}
	}

	// ---------------------------------------------------------------- run lifecycle

	public static void runStart(int threadsCount) {
		if (!ENABLED) {
			return;
		}
		DiagnosticCollector c = collector();
		synchronized (c.phaseWalls) {
			c.phaseWalls.clear();
		}
		c.runStartNano = System.nanoTime();
		c.phaseStartNano = c.runStartNano;
		c.processCpuStartNs = processCpuTime();
		c.configuredThreads = threadsCount;
		c.runStarted = true;
		Thread sampler = new Thread(c::samplerLoop, "jadx-diagnostic-sampler");
		sampler.setDaemon(true);
		sampler.start();
	}

	public static void registerClasses(RootNode root) {
		if (!ENABLED) {
			return;
		}
		DiagnosticCollector c = collector();
		for (ClassNode cls : root.getClasses(true)) {
			boolean topLevel = !cls.isInner();
			ClassNode top = cls.getTopParentClass();
			ClassStats stats = new ClassStats(c.classSeq.getAndIncrement(), cls.getFullName(),
					top == cls ? null : top.getFullName(), topLevel);
			c.classes.putIfAbsent(new ClassKey(cls), stats);
			if (topLevel) {
				c.topLevelRegistered++;
			}
			c.loadedAllRegistered++;
		}
	}

	public static void phase(String phase) {
		if (!ENABLED) {
			return;
		}
		collector().switchPhase(phase);
	}

	public static void runClosed() {
		if (!ENABLED) {
			return;
		}
		DiagnosticCollector c = collector();
		if (!c.runClosed) {
			c.runClosed = true;
			c.runClosedRelNs = System.nanoTime() - c.runStartNano;
			c.switchPhase("POST_SAVE");
		}
	}

	private void switchPhase(String newPhase) {
		long now = System.nanoTime();
		String oldPhase = currentPhase;
		long start = phaseStartNano;
		if (runStarted && now > start) {
			addPhase(oldPhase, now - start);
		}
		currentPhase = newPhase;
		phaseStartNano = now;
	}

	private void addPhase(String phase, long dtNs) {
		synchronized (phaseWalls) {
			phaseWalls.computeIfAbsent(phase, k -> new long[] { 0L })[0] += dtNs;
		}
	}

	// ---------------------------------------------------------------- reason scopes

	/** Push a request reason scope; returns previous token (may be null) for endReason. */
	public static Object beginReason(String reason) {
		DiagnosticCollector c = collector();
		String prev = c.requestReasonToken.get();
		c.requestReasonToken.set(reason);
		return prev;
	}

	public static void endReason(Object token) {
		DiagnosticCollector c = collector();
		if (token == null) {
			c.requestReasonToken.remove();
		} else {
			c.requestReasonToken.set((String) token);
		}
	}

	public static boolean inSaveIteration() {
		return collector().currentIteration.get() != null;
	}

	// ---------------------------------------------------------------- process instrumentation

	/** Count the invocation and return its start timestamp. */
	public static long processEntry(ClassNode cls, boolean codegen) {
		DiagnosticCollector c = collector();
		c.invocations.incrementAndGet();
		ClassStats cs = c.statsFor(cls);
		cs.invocations.incrementAndGet();
		int reason = c.currentReasonIdx();
		if (cs.firstReason.get() == -1) {
			cs.firstReason.compareAndSet(-1, reason);
		}
		cs.lastReason.set(reason);
		return System.nanoTime();
	}

	public static void processFastReturn(ClassNode cls, boolean codegen) {
		DiagnosticCollector c = collector();
		c.fastReturns.incrementAndGet();
		ClassStats cs = c.statsFor(cls);
		cs.fastReturns.incrementAndGet();
		c.reasonMetrics.incrementAndGet(c.currentReasonIdx() * M_METRICS + M_FAST_RETURNS);
	}

	/** Must be the first diagnostic action after acquiring the ClassInfo monitor. */
	public static Frame processAcquired(ClassNode cls, boolean codegen, long attemptNs, long callStartNs) {
		DiagnosticCollector c = collector();
		Frame f = new Frame();
		f.codegen = codegen;
		f.startNs = callStartNs;
		f.reasonIdx = c.currentReasonIdx();
		f.batchId = c.currentBatchId();
		f.prev = c.frameStack.get();
		ClassStats cs = c.statsFor(cls);
		f.cs = cs;
		long acquiredNs = System.nanoTime();
		f.acquiredNs = acquiredNs;
		f.waitNs = acquiredNs - attemptNs;
		cs.monitorAcquisitions.incrementAndGet();
		cs.waitNs.addAndGet(f.waitNs);
		updateMax(cs.maxWaitNs, f.waitNs);
		c.monitorAcquisitions.incrementAndGet();
		c.monitorWaitTotalNs.addAndGet(f.waitNs);
		updateMax(c.monitorWaitMaxNs, f.waitNs);
		if (f.waitNs > 100_000L) {
			c.waitOver100us.incrementAndGet();
		}
		if (f.waitNs > 1_000_000L) {
			c.waitOver1ms.incrementAndGet();
		}
		c.waitLog2Buckets.incrementAndGet(log2Bucket(f.waitNs));
		c.reasonMetrics.addAndGet(f.reasonIdx * M_METRICS + M_WAIT_NS, f.waitNs);
		BatchStats b = c.batch(f.batchId);
		if (b != null) {
			b.monitorWaitNs.addAndGet(f.waitNs);
		}
		IterFrame it = c.currentIteration.get();
		if (it != null) {
			it.waitAccum += f.waitNs;
		}
		c.frameStack.set(f);
		return f;
	}

	public static void visitorsBegin(ClassNode cls, Frame f) {
		if (f == null) {
			return;
		}
		DiagnosticCollector c = collector();
		f.visitorsStartNs = System.nanoTime();
		f.analysisOrdinal = (int) f.cs.analysisStarted.incrementAndGet();
		c.analysisStarted.incrementAndGet();
		c.reasonMetrics.incrementAndGet(f.reasonIdx * M_METRICS + M_ANALYSIS_STARTED);
		if (f.analysisOrdinal > 1) {
			c.reasonMetrics.incrementAndGet(f.reasonIdx * M_METRICS + M_REPEAT_ANALYSIS);
		}
	}

	public static void visitorsEnd(ClassNode cls, Frame f) {
		if (f == null) {
			return;
		}
		DiagnosticCollector c = collector();
		if (f.visitorsStartNs >= 0) {
			long ns = System.nanoTime() - f.visitorsStartNs;
			f.visitorsNs += ns;
			f.cs.visitorsNs.addAndGet(ns);
			c.reasonMetrics.addAndGet(f.reasonIdx * M_METRICS + M_VISITORS_NS, ns);
			if (f.analysisOrdinal > 1) {
				f.cs.repeatVisitorsNs.addAndGet(ns);
				c.reasonMetrics.addAndGet(f.reasonIdx * M_METRICS + M_REPEAT_VISITORS_NS, ns);
			}
			f.visitorsStartNs = -1;
		}
	}

	/** Mark a full visitor round as completed (state set to PROCESS_COMPLETE). */
	public static void analysisCompleted(ClassNode cls, Frame f) {
		if (f == null) {
			return;
		}
		f.cs.analysisCompleted.incrementAndGet();
		collector().analysisCompleted.incrementAndGet();
	}

	public static void codegenBegin(ClassNode cls, Frame f) {
		if (f == null) {
			return;
		}
		f.codegenStartNs = System.nanoTime();
	}

	public static void codegenEnd(ClassNode cls, Frame f) {
		if (f == null) {
			return;
		}
		if (f.codegenStartNs >= 0) {
			long ns = System.nanoTime() - f.codegenStartNs;
			f.codegenNs += ns;
			f.cs.codegenNs.addAndGet(ns);
			f.codegenStartNs = -1;
		}
	}

	/** Outer finally, executed while the monitor is still held. */
	public static void processEnd(ClassNode cls, Frame f) {
		if (f == null) {
			return;
		}
		DiagnosticCollector c = collector();
		long endNs = System.nanoTime();
		long heldNs = endNs - f.acquiredNs;
		long callElapsedNs = endNs - f.startNs;
		if (f.prev != null) {
			f.prev.childCallNs += callElapsedNs;
		}
		ClassStats cs = f.cs;
		cs.heldInclusiveNs.addAndGet(heldNs);
		long exclusiveNs = heldNs - f.childCallNs;
		if (exclusiveNs < 0) {
			c.recordingErrors.incrementAndGet();
			exclusiveNs = 0;
		}
		cs.heldExclusiveNs.addAndGet(exclusiveNs);
		c.reasonMetrics.incrementAndGet(f.reasonIdx * M_METRICS + M_COUNT);
		c.reasonMetrics.addAndGet(f.reasonIdx * M_METRICS + M_HELD_INCL_NS, heldNs);
		if (f.analysisOrdinal > 1) {
			cs.repeatHeldInclusiveNs.addAndGet(heldNs);
			long recoveryNs = exclusiveNs - f.codegenNs;
			if (recoveryNs < 0) {
				c.recordingErrors.incrementAndGet();
				recoveryNs = 0;
			}
			cs.repeatRecoveryNs.addAndGet(recoveryNs);
			c.reasonMetrics.addAndGet(f.reasonIdx * M_METRICS + M_RECOVERY_EXCL_NS, recoveryNs);
			BatchStats b = c.batch(f.batchId);
			if (b != null) {
				b.repeatRecoveryNs.addAndGet(recoveryNs);
			}
			IterFrame it = c.currentIteration.get();
			if (it != null) {
				it.recoveryAccum += recoveryNs;
			}
		} else if (f.analysisOrdinal == 0 && f.codegen) {
			c.reasonMetrics.addAndGet(f.reasonIdx * M_METRICS + M_CODEGEN_ONLY_NS, f.codegenNs);
		}
		c.frameStack.set(f.prev);
	}

	private ClassStats statsFor(ClassNode cls) {
		ClassKey key = new ClassKey(cls);
		ClassStats cs = classes.get(key);
		if (cs == null) {
			ClassNode top = cls.getTopParentClass();
			ClassStats created = new ClassStats(classSeq.getAndIncrement(), cls.getFullName(),
					top == cls ? null : top.getFullName(), top == cls);
			cs = classes.putIfAbsent(key, created);
			if (cs == null) {
				cs = created;
			}
		}
		return cs;
	}

	private int currentReasonIdx() {
		String reason = requestReasonToken.get();
		return reason == null ? Reason.UNKNOWN.ordinal() : Reason.indexOf(reason);
	}

	private int currentBatchId() {
		Integer id = originBatch.get();
		return id == null ? -1 : id;
	}

	private BatchStats batch(long id) {
		if (id < 0 || id >= batches.size()) {
			return null;
		}
		return batches.get((int) id);
	}

	private static void updateMax(AtomicLong max, long v) {
		long cur = max.get();
		while (v > cur && !max.compareAndSet(cur, v)) {
			cur = max.get();
		}
	}

	private static void updateMax(AtomicInteger max, int v) {
		int cur = max.get();
		while (v > cur && !max.compareAndSet(cur, v)) {
			cur = max.get();
		}
	}

	private static int log2Bucket(long ns) {
		if (ns <= 0) {
			return 0;
		}
		int b = 0;
		while ((ns >>= 1) != 0 && b < 47) {
			b++;
		}
		return b;
	}

	// ---------------------------------------------------------------- scheduler / batches

	/** Record the final plan (normal or fallback) at buildBatches return. */
	public static void recordPlan(boolean normal, int inputQueueSize, List<?> resultBatches, long buildNanos) {
		if (!ENABLED) {
			return;
		}
		DiagnosticCollector c = collector();
		for (Object batch : resultBatches) {
			int size = ((List<?>) batch).size();
			c.batches.add(new BatchStats(c.batchSeq.getAndIncrement(), size));
		}
	}

	/** Called when a source save task is appended; returns the stable batch id. */
	public static long batchScheduled(int size) {
		DiagnosticCollector c = collector();
		// called in plan order; cursor maps onto BatchStats rows created by recordPlan
		int idx = c.planBatchCursor.getAndIncrement();
		return Math.min(idx, c.batches.size() - 1);
	}

	public static Runnable wrapBatch(long batchId, Runnable original) {
		DiagnosticCollector c = collector();
		return () -> {
			Integer wid = c.workerId.get();
			if (wid == null) {
				wid = c.workerSeq.getAndIncrement();
				c.workerId.set(wid);
			}
			BatchStats b = c.batch(batchId);
			long[] iterationServices = b == null ? null : new long[b.size];
			if (b != null) {
				b.iterationServiceNs = iterationServices;
			}
			long start = System.nanoTime();
			if (b != null) {
				b.startRelNs = start - c.runStartNano;
			}
			// busy counting is owned by TaskExecutor.taskBegin/taskEnd (covers all stage types)
			c.originBatch.set((int) batchId);
			try {
				original.run();
			} finally {
				c.originBatch.remove();
				long end = System.nanoTime();
				if (b != null) {
					b.endRelNs = end - c.runStartNano;
					b.elapsedNs = end - start;
				}
			}
		};
	}

	// ---------------------------------------------------------------- per-class save iterations

	public static long iterationBegin(long batchId, int index) {
		DiagnosticCollector c = collector();
		IterFrame it = new IterFrame();
		it.startNs = System.nanoTime();
		it.batchId = (int) batchId;
		it.slot = index;
		c.currentIteration.set(it);
		return it.startNs;
	}

	/** @param token value returned by iterationBegin */
	public static void iterationEnd(long token, boolean failed) {
		DiagnosticCollector c = collector();
		IterFrame it = c.currentIteration.get();
		c.currentIteration.remove();
		if (it == null || it.startNs != token) {
			return;
		}
		long serviceNs = System.nanoTime() - it.startNs;
		BatchStats b = c.batch(it.batchId);
		if (b != null) {
			b.classServiceNs.addAndGet(serviceNs);
			b.iterationsDone.incrementAndGet();
			if (failed) {
				b.errors.incrementAndGet();
			}
			long[] services = b.iterationServiceNs;
			if (services != null && it.slot >= 0 && it.slot < services.length) {
				services[it.slot] = serviceNs;
			}
		}
		// per-iteration wait/recovery attribution stays in the batch aggregates (thread-local accumulators)
		it.waitAccum = 0;
		it.recoveryAccum = 0;
	}

	// ---------------------------------------------------------------- task executor stages

	public static void pendingStagePhase(String semanticPhase) {
		if (!ENABLED) {
			return;
		}
		collector().pendingStagePhaseKind.set(phaseKindOf(semanticPhase));
	}

	private static int phaseKindOf(String semanticPhase) {
		switch (semanticPhase) {
			case "SAVE_RESOURCES":
				return PHASE_RESOURCES;
			case "SAVE_SOURCES":
				return PHASE_SOURCES;
			case "PRE_DECOMPILE":
				return PHASE_PRE;
			default:
				return PHASE_SERIAL;
		}
	}

	public static int stagePlanned(String type, int taskCount) {
		DiagnosticCollector c = collector();
		Integer kind = c.pendingStagePhaseKind.get();
		c.pendingStagePhaseKind.remove();
		StageStats stage = new StageStats(c.stages.size(), type, kind == null ? PHASE_SERIAL : kind, taskCount);
		c.stages.add(stage);
		return stage.id;
	}

	public static void stageBegin(int stageId) {
		DiagnosticCollector c = collector();
		StageStats stage = c.stage(stageId);
		if (stage == null) {
			return;
		}
		stage.startRelNs = System.nanoTime() - c.runStartNano;
		c.currentStageKind = stage.phaseKind;
		c.switchPhase(phaseName(stage.phaseKind));
	}

	public static void stageEnd(int stageId, boolean terminating) {
		DiagnosticCollector c = collector();
		StageStats stage = c.stage(stageId);
		if (stage == null) {
			return;
		}
		stage.endRelNs = System.nanoTime() - c.runStartNano;
		stage.drained = !terminating;
	}

	public static void poolRegistered(int stageId, int threads, ExecutorService pool) {
		DiagnosticCollector c = collector();
		StageStats stage = c.stage(stageId);
		if (stage != null) {
			stage.poolThreads = threads;
		}
		if (pool instanceof ThreadPoolExecutor) {
			c.savePool = (ThreadPoolExecutor) pool;
		}
	}

	public static long taskBegin() {
		DiagnosticCollector c = collector();
		long start = System.nanoTime();
		StageStats stage = c.currentOpenStage();
		if (stage != null) {
			stage.tasksStarted.incrementAndGet();
		}
		switch (c.currentStageKind) {
			case PHASE_RESOURCES:
				c.busyResources.incrementAndGet();
				break;
			case PHASE_SOURCES:
				c.busySources.incrementAndGet();
				break;
			case PHASE_PRE:
				c.busyPre.incrementAndGet();
				break;
			default:
				c.busySerial.incrementAndGet();
				break;
		}
		return start;
	}

	public static void taskEnd(long token) {
		if (token == 0) {
			return;
		}
		DiagnosticCollector c = collector();
		long elapsed = System.nanoTime() - token;
		StageStats stage = c.currentOpenStage();
		if (stage != null) {
			stage.busyWorkerNs.addAndGet(elapsed);
			stage.tasksFinished.incrementAndGet();
			updateMax(stage.peakBusy, busyOf(c, stage.phaseKind));
		}
		switch (c.currentStageKind) {
			case PHASE_RESOURCES:
				c.busyResources.decrementAndGet();
				break;
			case PHASE_SOURCES:
				c.busySources.decrementAndGet();
				break;
			case PHASE_PRE:
				c.busyPre.decrementAndGet();
				break;
			default:
				c.busySerial.decrementAndGet();
				break;
		}
	}

	public static void taskSkipped() {
		DiagnosticCollector c = collector();
		StageStats stage = c.currentOpenStage();
		if (stage != null) {
			stage.tasksSkipped.incrementAndGet();
		}
	}

	private StageStats currentOpenStage() {
		for (int i = stages.size() - 1; i >= 0; i--) {
			StageStats s = stages.get(i);
			if (s.startRelNs >= 0 && s.endRelNs < 0) {
				return s;
			}
		}
		return null;
	}

	private StageStats stage(int id) {
		if (id < 0 || id >= stages.size()) {
			return null;
		}
		return stages.get(id);
	}

	private static int busyOf(DiagnosticCollector c, int kind) {
		switch (kind) {
			case PHASE_RESOURCES:
				return c.busyResources.get();
			case PHASE_SOURCES:
				return c.busySources.get();
			case PHASE_PRE:
				return c.busyPre.get();
			default:
				return c.busySerial.get();
		}
	}

	private static String phaseName(int kind) {
		switch (kind) {
			case PHASE_RESOURCES:
				return "SAVE_RESOURCES";
			case PHASE_SOURCES:
				return "SAVE_SOURCES";
			case PHASE_PRE:
				return "PRE_DECOMPILE";
			default:
				return "SAVE_FINAL_SEQUENTIAL";
		}
	}

	// ---------------------------------------------------------------- sampler

	private void samplerLoop() {
		long lastTick = System.nanoTime();
		long lastCpu = processCpuTime();
		while (!runClosed) {
			long now = System.nanoTime();
			long next = lastTick + TICK_INTERVAL_NS;
			if (now < next) {
				try {
					long sleepMs = (next - now) / 1_000_000L;
					Thread.sleep(Math.max(1, sleepMs));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				continue;
			}
			lastTick = recordTick(lastTick, now, lastCpu);
			lastCpu = processCpuTime();
		}
	}

	private long recordTick(long lastTick, long now, long lastCpu) {
		long cpu = processCpuTime();
		ThreadPoolExecutor pool = savePool;
		int queue = 0;
		if (pool != null) {
			try {
				queue = pool.getQueue().size();
			} catch (Exception ignored) {
				// executor may be shutting down
			}
		}
		long lastCpuVal = lastCpu;
		timeline.add(new Tick(now - runStartNano, now - lastTick, currentPhase,
				busySources.get(), busyResources.get(), busyPre.get(), busySerial.get(),
				queue, cpu, cpu < 0 || lastCpuVal < 0 ? -1 : cpu - lastCpuVal));
		return now;
	}

	private static long processCpuTime() {
		try {
			java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
			if (os instanceof com.sun.management.OperatingSystemMXBean) {
				long t = ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
				return t >= 0 ? t : -1;
			}
		} catch (Throwable ignored) {
			// fall through
		}
		return -1;
	}

	// ---------------------------------------------------------------- shutdown write

	private void shutdownWrite() {
		if (outputPath == null) {
			System.err.println("[jadx-diagnostic] CONFIGURATION ERROR: jadx.diagnostic=true but "
					+ "jadx.diagnostic.output is missing/empty; no diagnostic JSON written.");
			return;
		}
		// final partial tick
		if (runStarted) {
			long now = System.nanoTime();
			long lastTick = timeline.isEmpty() ? runStartNano : runStartNano + timeline.get(timeline.size() - 1).relNs;
			if (now > lastTick) {
				long lastCpu = timeline.isEmpty() ? processCpuStartNs : timeline.get(timeline.size() - 1).cpuNs;
				recordTick(lastTick, now, lastCpu);
			}
		}
		processCpuEndNs = processCpuTime();
		Path out = Paths.get(outputPath);
		Path tmp = out.resolveSibling(out.getFileName() + ".tmp-diag");
		String writeStatus = "ok";
		try {
			if (out.getParent() != null) {
				Files.createDirectories(out.getParent());
			}
			try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				JsonWriter json = new JsonWriter(w);
				json.setIndent(" ");
				writeJson(json);
			}
			try {
				Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException unsupportedAtomic) {
				Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
				writeStatus = "ok_non_atomic_move";
			}
		} catch (Exception e) {
			System.err.println("[jadx-diagnostic] FAILED to write diagnostic JSON to " + out + ": " + e);
			return;
		}
		writeStderrSummary(out, writeStatus);
	}

	private void writeStderrSummary(Path out, String writeStatus) {
		// <=4 lines per spec
		long[] invHist = new long[4];
		long[] anaHist = new long[4];
		histograms(invHist, anaHist);
		long fullRepeats = analysisStarted.get() - classesWithAnalysis();
		long repeatRecoveryNs = 0;
		for (Reason r : Reason.values()) {
			repeatRecoveryNs += reasonMetrics.get(r.ordinal() * M_METRICS + M_RECOVERY_EXCL_NS);
		}
		System.err.println("[jadx-diagnostic] diagnostic (non-timed) run complete=" + runClosed
				+ " write=" + writeStatus + " json=" + out.toAbsolutePath());
		System.err.println(String.format(
				"[jadx-diagnostic] invocations 1x/2x/3x+=%d/%d/%d zero=%d, analysis 1x/2x/3x+=%d/%d/%d, fullRepeats=%d, repeatRecoveryWallProxy=%.2fs",
				invHist[1], invHist[2], invHist[3], invHist[0],
				anaHist[1], anaHist[2], anaHist[3], fullRepeats, repeatRecoveryNs / 1e9));
		writeBatchSummaryLine();
		System.err.println(String.format(
				"[jadx-diagnostic] ClassInfo monitor: acquisitions=%d waitTotal=%.3fs max=%.3fs over100us=%d over1ms=%d",
				monitorAcquisitions.get(), monitorWaitTotalNs.get() / 1e9,
				monitorWaitMaxNs.get() / 1e9, waitOver100us.get(), waitOver1ms.get()));
	}

	private void writeBatchSummaryLine() {
		long[] sizes = batchSizes();
		int n = sizes.length;
		long longestNs = 0;
		long longestId = -1;
		for (BatchStats b : batches) {
			if (b.elapsedNs > longestNs) {
				longestNs = b.elapsedNs;
				longestId = b.id;
			}
		}
		long[] util = sourcesUtilization();
		if (n == 0) {
			System.err.println("[jadx-diagnostic] batches: none");
			return;
		}
		System.err.println(String.format(
				"[jadx-diagnostic] batches=%d size min/med/p90/max=%d/%d/%d/%d longest=%.2fs (id=%d) sourceMeanBusy=%.2f/8",
				n, sizes[0], median(sizes), percentile90(sizes), sizes[n - 1],
				longestNs / 1e9, longestId, util[0] / 1_000_000.0));
	}

	// ---------------------------------------------------------------- JSON serialization

	private void writeJson(JsonWriter json) throws IOException {
		json.beginObject();
		json.name("schema_version").value(1);
		json.name("diagnostic").value(true);
		json.name("timed_comparison_eligible").value(false);
		json.name("source_commit").value(SOURCE_COMMIT);
		json.name("build_revision").value(jadx.core.Jadx.getVersion());
		json.name("jvm").value(System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
		json.name("host").value(hostName());
		json.name("os").value(System.getProperty("os.name") + " " + System.getProperty("os.arch"));
		json.name("available_processors").value(Runtime.getRuntime().availableProcessors());
		json.name("output_path").value(outputPath);
		json.name("collector_status").value(recordingErrors.get() == 0 ? "ok" : "recording_errors=" + recordingErrors.get());
		json.name("write_status").value("ok");
		json.name("runs");
		json.beginArray();
		writeRun(json);
		json.endArray();
		json.endObject();
	}

	private static String hostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (Exception e) {
			return "unknown";
		}
	}

	private void writeRun(JsonWriter json) throws IOException {
		json.beginObject();
		json.name("metadata");
		json.beginObject();
		json.name("threads").value(configuredThreads);
		json.name("run_closed").value(runClosed);
		json.name("loaded_top_level_classes").value(topLevelRegistered);
		json.name("loaded_all_classes").value(loadedAllRegistered);
		json.name("tracked_classes").value(classes.size());
		json.endObject();

		json.name("status");
		json.beginObject();
		json.name("run_closed").value(runClosed);
		json.name("snapshot_consistent").value(runClosed);
		json.name("recording_errors").value(recordingErrors.get());
		json.endObject();

		json.name("durations");
		json.beginObject();
		json.name("total_observed_wall_ns").value(runClosedRelNs > 0 ? runClosedRelNs : System.nanoTime() - runStartNano);
		json.name("process_cpu_start_ns").value(processCpuStartNs);
		json.name("process_cpu_end_ns").value(processCpuEndNs);
		json.name("phases");
		json.beginObject();
		synchronized (phaseWalls) {
			for (Map.Entry<String, long[]> e : phaseWalls.entrySet()) {
				json.name(e.getKey()).value(e.getValue()[0]);
			}
		}
		json.endObject();
		json.endObject();

		writeProcessing(json);
		writeScheduler(json);
		writeStages(json);
		writeTimeline(json);
		writeLocks(json);
		json.endObject();
	}

	private void writeProcessing(JsonWriter json) throws IOException {
		json.name("processing");
		json.beginObject();
		long[] invHist = new long[4];
		long[] anaHist = new long[4];
		histograms(invHist, anaHist);
		long classesWithAnalysis = classesWithAnalysis();
		json.name("totals");
		json.beginObject();
		json.name("invocations").value(invocations.get());
		json.name("raw_reentries").value(invocations.get() - classes.size());
		json.name("analysis_rounds_started").value(analysisStarted.get());
		json.name("analysis_rounds_completed").value(analysisCompleted.get());
		json.name("classes_with_analysis").value(classesWithAnalysis);
		json.name("full_reprocessing_count").value(analysisStarted.get() - classesWithAnalysis);
		json.name("complete_fast_returns").value(fastReturns.get());
		json.name("repeat_recovery_exclusive_ns").value(repeatRecoveryTotalNs());
		json.name("repeat_visitors_exclusive_ns").value(repeatVisitorsTotalNs());
		json.name("recording_errors").value(recordingErrors.get());
		json.endObject();
		json.name("histograms");
		json.beginObject();
		json.name("invocation_zero").value(invHist[0]);
		json.name("invocation_one").value(invHist[1]);
		json.name("invocation_two").value(invHist[2]);
		json.name("invocation_three_plus").value(invHist[3]);
		json.name("analysis_zero").value(classes.size() - classesWithAnalysis);
		json.name("analysis_one").value(anaHist[1]);
		json.name("analysis_two").value(anaHist[2]);
		json.name("analysis_three_plus").value(anaHist[3]);
		json.endObject();
		json.name("by_reason");
		json.beginArray();
		for (Reason r : Reason.values()) {
			json.beginObject();
			json.name("reason").value(r.name());
			for (int m = 0; m < M_METRICS; m++) {
				long v = reasonMetrics.get(r.ordinal() * M_METRICS + m);
				if (v != 0) {
					json.name(metricName(m)).value(v);
				}
			}
			json.endObject();
		}
		json.endArray();
		json.name("classes");
		json.beginArray();
		for (ClassStats cs : sortedClasses()) {
			json.beginObject();
			json.name("class_id").value(cs.id);
			json.name("raw_name").value(cs.rawName);
			json.name("top_parent").value(cs.topParentName);
			json.name("is_top_level").value(cs.topLevel);
			json.name("invocations").value(cs.invocations.get());
			json.name("analysis_started").value(cs.analysisStarted.get());
			json.name("analysis_completed").value(cs.analysisCompleted.get());
			json.name("fast_returns").value(cs.fastReturns.get());
			json.name("monitor_acquisitions").value(cs.monitorAcquisitions.get());
			json.name("monitor_wait_ns").value(cs.waitNs.get());
			json.name("monitor_wait_max_ns").value(cs.maxWaitNs.get());
			json.name("held_inclusive_ns").value(cs.heldInclusiveNs.get());
			json.name("held_exclusive_ns").value(cs.heldExclusiveNs.get());
			json.name("repeat_held_inclusive_ns").value(cs.repeatHeldInclusiveNs.get());
			json.name("visitors_ns").value(cs.visitorsNs.get());
			json.name("repeat_visitors_ns").value(cs.repeatVisitorsNs.get());
			json.name("codegen_ns").value(cs.codegenNs.get());
			json.name("repeat_recovery_exclusive_ns").value(cs.repeatRecoveryNs.get());
			json.name("first_reason").value(reasonName(cs.firstReason.get()));
			json.name("last_reason").value(reasonName(cs.lastReason.get()));
			json.endObject();
		}
		json.endArray();
		json.endObject();
	}

	private static String metricName(int m) {
		switch (m) {
			case M_COUNT:
				return "count";
			case M_FAST_RETURNS:
				return "fast_returns";
			case M_ANALYSIS_STARTED:
				return "analysis_started";
			case M_REPEAT_ANALYSIS:
				return "repeat_analysis";
			case M_WAIT_NS:
				return "wait_ns";
			case M_HELD_INCL_NS:
				return "held_inclusive_ns";
			case M_VISITORS_NS:
				return "visitors_ns";
			case M_REPEAT_VISITORS_NS:
				return "repeat_visitors_ns";
			case M_RECOVERY_EXCL_NS:
				return "recovery_exclusive_ns";
			case M_CODEGEN_ONLY_NS:
				return "codegen_only_ns";
			default:
				return "m" + m;
		}
	}

	private static String reasonName(int idx) {
		return idx < 0 ? "NONE" : Reason.values()[idx].name();
	}

	private List<ClassStats> sortedClasses() {
		List<ClassStats> list = new ArrayList<>(classes.size());
		list.addAll(classes.values());
		list.sort(Comparator.comparingInt(cs -> cs.id));
		return list;
	}

	private void histograms(long[] invHist, long[] anaHist) {
		for (ClassStats cs : classes.values()) {
			invHist[bucketOf(cs.invocations.get())]++;
			anaHist[bucketOf(cs.analysisStarted.get())]++;
		}
	}

	private static int bucketOf(long count) {
		if (count <= 0) {
			return 0;
		}
		if (count == 1) {
			return 1;
		}
		return count == 2 ? 2 : 3;
	}

	private long classesWithAnalysis() {
		long n = 0;
		for (ClassStats cs : classes.values()) {
			if (cs.analysisStarted.get() > 0) {
				n++;
			}
		}
		return n;
	}

	private long repeatRecoveryTotalNs() {
		long total = 0;
		for (int r = 0; r < REASONS; r++) {
			total += reasonMetrics.get(r * M_METRICS + M_RECOVERY_EXCL_NS);
		}
		return total;
	}

	private long repeatVisitorsTotalNs() {
		long total = 0;
		for (int r = 0; r < REASONS; r++) {
			total += reasonMetrics.get(r * M_METRICS + M_REPEAT_VISITORS_NS);
		}
		return total;
	}

	private void writeScheduler(JsonWriter json) throws IOException {
		json.name("scheduler");
		json.beginObject();
		long[] sizes = batchSizes();
		int n = sizes.length;
		json.name("plans");
		json.beginArray();
		json.beginObject();
		json.name("plan_id").value(0);
		json.name("mode").value("default");
		json.name("batch_count").value(n);
		json.name("sum_batch_sizes").value(sumSizes());
		json.name("size_min").value(n == 0 ? -1 : sizes[0]);
		json.name("size_median").value(median(sizes));
		json.name("size_p90").value(percentile90(sizes));
		json.name("size_max").value(n == 0 ? -1 : sizes[n - 1]);
		json.endObject();
		json.endArray();
		long longestNs = 0;
		long longestId = -1;
		long longestSize = -1;
		long sumElapsed = 0;
		long minStart = Long.MAX_VALUE;
		long maxEnd = Long.MIN_VALUE;
		json.name("batches");
		json.beginArray();
		for (BatchStats b : batches) {
			json.beginObject();
			json.name("batch_id").value(b.id);
			json.name("size").value(b.size);
			json.name("start_rel_ns").value(b.startRelNs);
			json.name("end_rel_ns").value(b.endRelNs);
			json.name("elapsed_ns").value(b.elapsedNs);
			json.name("class_service_sum_ns").value(b.classServiceNs.get());
			json.name("monitor_wait_ns_attributed").value(b.monitorWaitNs.get());
			json.name("repeat_recovery_exclusive_ns_attributed").value(b.repeatRecoveryNs.get());
			json.name("iterations_done").value(b.iterationsDone.get());
			json.name("errors").value(b.errors.get());
			if (b.iterationServiceNs != null) {
				json.name("iteration_service_ns");
				json.beginArray();
				for (long v : b.iterationServiceNs) {
					json.value(v);
				}
				json.endArray();
			}
			json.endObject();
			if (b.elapsedNs > 0) {
				if (b.elapsedNs > longestNs) {
					longestNs = b.elapsedNs;
					longestId = b.id;
					longestSize = b.size;
				}
				sumElapsed += b.elapsedNs;
				minStart = Math.min(minStart, b.startRelNs);
				maxEnd = Math.max(maxEnd, b.endRelNs);
			}
		}
		json.endArray();
		json.name("execution");
		json.beginObject();
		json.name("sum_batch_elapsed_ns").value(sumElapsed);
		json.name("longest_batch_ns").value(longestNs);
		json.name("longest_batch_id").value(longestId);
		json.name("longest_batch_class_count").value(longestSize);
		json.name("source_makespan_ns").value(minStart == Long.MAX_VALUE ? 0 : maxEnd - minStart);
		json.endObject();
		json.endObject();
	}

	private long sumSizes() {
		long sum = 0;
		for (BatchStats b : batches) {
			sum += b.size;
		}
		return sum;
	}

	private long[] batchSizes() {
		long[] sizes = new long[batches.size()];
		for (int i = 0; i < sizes.length; i++) {
			sizes[i] = batches.get(i).size;
		}
		Arrays.sort(sizes);
		return sizes;
	}

	/** nearest-rank median: ceil(0.5*n)-th item of ascending order */
	private static long median(long[] sorted) {
		if (sorted.length == 0) {
			return -1;
		}
		return sorted[(int) Math.ceil(0.5 * sorted.length) - 1];
	}

	private static long percentile90(long[] sorted) {
		if (sorted.length == 0) {
			return -1;
		}
		return sorted[(int) Math.ceil(0.9 * sorted.length) - 1];
	}

	private void writeStages(JsonWriter json) throws IOException {
		json.name("stages");
		json.beginArray();
		for (StageStats s : stages) {
			json.beginObject();
			json.name("stage_id").value(s.id);
			json.name("type").value(s.type);
			json.name("semantic_phase").value(phaseName(s.phaseKind));
			json.name("tasks_planned").value(s.tasksCount);
			json.name("tasks_started").value(s.tasksStarted.get());
			json.name("tasks_finished").value(s.tasksFinished.get());
			json.name("tasks_skipped").value(s.tasksSkipped.get());
			json.name("start_rel_ns").value(s.startRelNs);
			json.name("end_rel_ns").value(s.endRelNs);
			json.name("wall_ns").value(s.startRelNs < 0 || s.endRelNs < 0 ? -1 : s.endRelNs - s.startRelNs);
			json.name("drained").value(s.drained);
			json.name("busy_worker_ns").value(s.busyWorkerNs.get());
			json.name("peak_busy").value(s.peakBusy.get());
			json.name("pool_threads").value(s.poolThreads);
			json.endObject();
		}
		json.endArray();
	}

	private void writeTimeline(JsonWriter json) throws IOException {
		json.name("timeline");
		json.beginArray();
		for (Tick t : timeline) {
			json.beginObject();
			json.name("rel_ns").value(t.relNs);
			json.name("dt_ns").value(t.dtNs);
			json.name("phase").value(t.phase);
			json.name("busy_sources").value(t.busySources);
			json.name("busy_resources").value(t.busyResources);
			json.name("busy_pre").value(t.busyPre);
			json.name("busy_serial").value(t.busySerial);
			json.name("queue_size").value(t.queueSize);
			json.name("process_cpu_ns").value(t.cpuNs);
			json.name("process_cpu_delta_ns").value(t.cpuDeltaNs);
			json.endObject();
		}
		json.endArray();
	}

	private void writeLocks(JsonWriter json) throws IOException {
		json.name("locks");
		json.beginObject();
		json.name("class_info");
		json.beginObject();
		json.name("coverage").value("ProcessClass.process entry only");
		json.name("acquisitions").value(monitorAcquisitions.get());
		json.name("wait_total_ns").value(monitorWaitTotalNs.get());
		json.name("wait_max_ns").value(monitorWaitMaxNs.get());
		json.name("wait_over_100us_count").value(waitOver100us.get());
		json.name("wait_over_1ms_count").value(waitOver1ms.get());
		json.name("wait_log2_ns_buckets");
		json.beginArray();
		for (int i = 0; i < waitLog2Buckets.length(); i++) {
			json.value(waitLog2Buckets.get(i));
		}
		json.endArray();
		json.endObject();
		json.endObject();
	}

	/** [0]=mean busy sources workers scaled by 1e6, [1]=sources phase wall ns. */
	private long[] sourcesUtilization() {
		long busyWeighted = 0;
		long wall = 0;
		for (Tick t : timeline) {
			if ("SAVE_SOURCES".equals(t.phase) && t.dtNs > 0) {
				busyWeighted += (long) t.busySources * t.dtNs;
				wall += t.dtNs;
			}
		}
		return new long[] { wall == 0 ? 0 : (long) Math.round((double) busyWeighted / wall * 1_000_000), wall };
	}
}
