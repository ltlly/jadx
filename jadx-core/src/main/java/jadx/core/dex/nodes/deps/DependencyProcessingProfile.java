package jadx.core.dex.nodes.deps;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;

/** Opt-in, per-root diagnostic accounting; never used to select or skip work. */
public final class DependencyProcessingProfile {
	private static final Logger LOG = LoggerFactory.getLogger(DependencyProcessingProfile.class);

	public static final String ENABLE_PROPERTY = "jadx.deps.profile";
	public static final String OUTPUT_PROPERTY = "jadx.deps.profile.output";
	private static final int MAX_TRACE_EVENTS = 1_000_000;

	private final LongSupplier wallClock;
	private final LongSupplier cpuClock;
	private final ThreadLocal<Span> current = new ThreadLocal<>();
	private final Map<String, Totals> stages = new ConcurrentHashMap<>();
	private final Map<String, Totals> classes = new ConcurrentHashMap<>();
	private final Map<String, LongAdder> reads = new ConcurrentHashMap<>();
	private final Map<String, LongAdder> decodes = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<String> trace = new ConcurrentLinkedQueue<>();
	private final AtomicLong sequence = new AtomicLong();
	private final AtomicLong traceCount = new AtomicLong();
	private final LongAdder droppedTrace = new LongAdder();
	private final LongAdder active = new LongAdder();
	private final LongAdder invalidScopes = new LongAdder();
	private final AtomicBoolean dumped = new AtomicBoolean();
	private final long origin;
	private final long originUptimeMs;
	private final @Nullable String output;

	public DependencyProcessingProfile() {
		this(System::nanoTime, makeCpuClock());
	}

	DependencyProcessingProfile(LongSupplier wallClock, LongSupplier cpuClock) {
		this.wallClock = wallClock;
		this.cpuClock = cpuClock;
		this.origin = wallClock.getAsLong();
		this.originUptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
		this.output = System.getProperty(OUTPUT_PROPERTY);
	}

	private static LongSupplier makeCpuClock() {
		try {
			ThreadMXBean bean = ManagementFactory.getThreadMXBean();
			if (bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()) {
				return bean::getCurrentThreadCpuTime;
			}
		} catch (UnsupportedOperationException | SecurityException e) {
			LOG.warn("Dependency profile CPU clock unavailable", e);
		}
		return () -> -1L;
	}

	private long cpuNow() {
		try {
			return cpuClock.getAsLong();
		} catch (RuntimeException e) {
			return -1L;
		}
	}

	public Span begin(ClassNode cls, String stage, boolean recovery, boolean traceEvent) {
		Span span = new Span(cls.getTopParentClass(), stage, recovery, traceEvent, current.get());
		current.set(span);
		active.increment();
		return span;
	}

	public Span beginStage(ClassNode cls, String stage) {
		Span parent = current.get();
		return begin(cls, stage, parent != null && parent.recovery, false);
	}

	public void recordBodyRead(MethodNode mth, String accessor, boolean present) {
		Span caller = current.get();
		if (caller == null) {
			return;
		}
		ClassNode target = mth.getTopParentClass();
		if (caller.owner == target) {
			return;
		}
		String key = field(caller.stage) + '\t' + caller.recovery + '\t' + field(caller.className)
				+ '\t' + field(accessor) + '\t' + field(mth.getParentClass().getRawName()) + '\t'
				+ field(mth.getMethodInfo().makeSignature(true)) + '\t' + target.getState()
				+ '\t' + present;
		reads.computeIfAbsent(key, k -> new LongAdder()).increment();
	}

	public void recordDecodeAttempt(MethodNode mth) {
		Span caller = current.get();
		String key = caller == null ? "OUTSIDE_PROCESS\tfalse" : field(caller.stage) + '\t' + caller.recovery;
		key += "\t" + mth.getTopParentClass().getState();
		decodes.computeIfAbsent(key, k -> new LongAdder()).increment();
	}

	public void dumpIfRequested() {
		if (output == null || !dumped.compareAndSet(false, true)) {
			return;
		}
		try {
			writeReport(Path.of(output));
		} catch (Throwable e) {
			// Diagnostic output failure must not change decompilation's exit code.
			LOG.warn("Failed to write dependency processing profile", e);
		}
	}

	public void writeReport(Path directory) throws IOException {
		Files.createDirectories(directory);
		writeTotals(directory.resolve("stages.tsv"), "stage", stages);
		writeTotals(directory.resolve("classes.tsv"), "class", classes);
		try (BufferedWriter writer = newWriter(directory.resolve("body-reads.tsv"))) {
			writer.write(
					"caller_stage\tcaller_recovery\tcaller_class\taccessor\ttarget_class\ttarget_method\ttarget_top_state\tpresent\tcalls\n");
			for (Map.Entry<String, LongAdder> entry : new TreeMap<>(reads).entrySet()) {
				writer.write(entry.getKey() + '\t' + entry.getValue().sum() + '\n');
			}
		}
		try (BufferedWriter writer = newWriter(directory.resolve("decode-attempts.tsv"))) {
			writer.write("caller_stage\tcaller_recovery\ttarget_state\tcalls\n");
			for (Map.Entry<String, LongAdder> entry : new TreeMap<>(decodes).entrySet()) {
				writer.write(entry.getKey() + '\t' + entry.getValue().sum() + '\n');
			}
		}
		try (BufferedWriter writer = newWriter(directory.resolve("timeline.tsv"))) {
			writer.write("id\tparent_id\tthread_id\tclass\tstage\trecovery\tstart_ns\tend_ns\tlock_acquire_ns\n");
			for (String event : trace) {
				writer.write(event);
			}
		}
		try (BufferedWriter writer = newWriter(directory.resolve("status.tsv"))) {
			writer.write("active_scopes\tinvalid_scopes\tdropped_trace_events\torigin_uptime_ms\n");
			writer.write(active.sum() + "\t" + invalidScopes.sum() + "\t" + droppedTrace.sum() + '\t' + originUptimeMs + '\n');
		}
	}

	private static BufferedWriter newWriter(Path path) throws IOException {
		return Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
	}

	private static void writeTotals(Path path, String dimension, Map<String, Totals> totals) throws IOException {
		try (BufferedWriter writer = newWriter(path)) {
			writer.write(dimension + "\trecovery\tcalls\tcpu_known_calls\tcpu_inclusive_ns\tcpu_self_ns"
					+ "\twall_inclusive_ns\twall_self_ns\tlock_acquire_ns\n");
			for (Map.Entry<String, Totals> entry : new TreeMap<>(totals).entrySet()) {
				Totals value = entry.getValue();
				writer.write(entry.getKey() + '\t' + value.calls.sum() + '\t' + value.cpuKnown.sum()
						+ '\t' + value.cpuInclusive.sum() + '\t' + value.cpuSelf.sum()
						+ '\t' + value.wallInclusive.sum() + '\t' + value.wallSelf.sum()
						+ '\t' + value.lockAcquire.sum() + '\n');
			}
		}
	}

	private static String field(String value) {
		return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
	}

	public final class Span implements AutoCloseable {
		private final ClassNode owner;
		private final String className;
		private final String stage;
		private final @Nullable Span parent;
		private final long id;
		private final long thread;
		private final long startWall;
		private final long startCpu;
		private final boolean traceEvent;
		private boolean recovery;
		private boolean closed;
		private long childWall;
		private long childCpu;
		private long lockAcquire;

		private Span(ClassNode owner, String stage, boolean recovery, boolean traceEvent, @Nullable Span parent) {
			this.owner = owner;
			this.className = owner.getRawName();
			this.stage = stage;
			this.recovery = recovery;
			this.traceEvent = traceEvent;
			this.parent = parent;
			this.id = traceEvent ? sequence.incrementAndGet() : 0L;
			this.thread = Thread.currentThread().getId();
			this.startWall = wallClock.getAsLong();
			this.startCpu = cpuNow();
		}

		public void acquiredMonitor(boolean isRecovery) {
			lockAcquire = wallClock.getAsLong() - startWall;
			recovery = isRecovery;
		}

		@Override
		public void close() {
			if (closed) {
				return;
			}
			if (current.get() != this) {
				invalidScopes.increment();
				return;
			}
			closed = true;
			long endWall = wallClock.getAsLong();
			long endCpu = cpuNow();
			long wall = endWall - startWall;
			boolean cpuKnown = startCpu >= 0 && endCpu >= startCpu;
			long cpu = cpuKnown ? endCpu - startCpu : 0L;
			if (wall < childWall || cpuKnown && cpu < childCpu) {
				invalidScopes.increment();
			}
			String suffix = "\t" + recovery;
			stages.computeIfAbsent(field(stage) + suffix, k -> new Totals())
					.add(cpuKnown, cpu, Math.max(0L, cpu - childCpu), wall, Math.max(0L, wall - childWall), lockAcquire);
			if (!stage.equals("GENERATE_REQUEST")) {
				classes.computeIfAbsent(field(className) + suffix, k -> new Totals())
						.add(cpuKnown, cpu, Math.max(0L, cpu - childCpu), wall, Math.max(0L, wall - childWall), lockAcquire);
			}
			if (parent == null) {
				current.remove();
			} else {
				parent.childWall += wall;
				parent.childCpu += cpu;
				current.set(parent);
			}
			active.decrement();
			if (traceEvent) {
				if (traceCount.incrementAndGet() <= MAX_TRACE_EVENTS) {
					Span traceParent = parent;
					while (traceParent != null && !traceParent.traceEvent) {
						traceParent = traceParent.parent;
					}
					trace.add(id + "\t" + (traceParent == null ? 0L : traceParent.id) + '\t' + thread
							+ '\t' + field(className) + '\t' + field(stage) + '\t' + recovery
							+ '\t' + (startWall - origin) + '\t' + (endWall - origin) + '\t' + lockAcquire + '\n');
				} else {
					droppedTrace.increment();
				}
			}
		}
	}

	private static final class Totals {
		private final LongAdder calls = new LongAdder();
		private final LongAdder cpuKnown = new LongAdder();
		private final LongAdder cpuInclusive = new LongAdder();
		private final LongAdder cpuSelf = new LongAdder();
		private final LongAdder wallInclusive = new LongAdder();
		private final LongAdder wallSelf = new LongAdder();
		private final LongAdder lockAcquire = new LongAdder();

		private void add(boolean known, long cpu, long ownCpu, long wall, long ownWall, long wait) {
			calls.increment();
			if (known) {
				cpuKnown.increment();
				cpuInclusive.add(cpu);
				cpuSelf.add(ownCpu);
			}
			wallInclusive.add(wall);
			wallSelf.add(ownWall);
			lockAcquire.add(wait);
		}
	}
}
