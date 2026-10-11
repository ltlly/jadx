package jadx.core.dex.visitors;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import jadx.core.dex.attributes.nodes.MethodInlineAttr;
import jadx.core.dex.instructions.IndexInsnNode;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.InvokeNode;
import jadx.core.dex.instructions.InvokeType;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.visitors.usage.UsageInfoVisitor;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class InlineMethodsUsageTest extends SmaliTest {

	private enum GatePoint {
		BRIDGE, METHOD, FIELD, CLASS
	}

	@Test
	void concurrentInliningRemovesEveryBridgeCaller() throws Exception {
		checkConcurrentInlining(GatePoint.BRIDGE);
	}

	@Test
	void concurrentInliningPreservesTargetMethodCallers() throws Exception {
		checkConcurrentInlining(GatePoint.METHOD);
	}

	@Test
	void concurrentInliningPreservesTargetFieldCallers() throws Exception {
		checkConcurrentInlining(GatePoint.FIELD);
	}

	@Test
	void concurrentInliningPreservesClassCallers() throws Exception {
		checkConcurrentInlining(GatePoint.CLASS);
	}

	@Test
	void usageSnapshotsDetachInputsAndRemainStable() {
		RootNode root = loadFixture();
		ClassNode owner = root.resolveClass("inline.concurrent.Target");
		MethodNode target = getMethod(owner, "target");
		FieldNode field = getField(owner, "value");
		MethodNode bridge = getMethod(root.resolveClass("inline.concurrent.Bridge"), "bridge");
		MethodNode left = getMethod(root.resolveClass("inline.concurrent.Left"), "call");
		MethodNode right = getMethod(root.resolveClass("inline.concurrent.Right"), "call");
		left.setUsed(List.of());
		List<MethodNode> inputMethods = new ArrayList<>(List.of(left));
		List<ClassNode> inputClasses = new ArrayList<>(List.of(left.getParentClass()));
		target.setUseIn(inputMethods);
		field.setUseIn(inputMethods);
		owner.setUseInMth(inputMethods);
		owner.setUseIn(inputClasses);
		List<MethodNode> methodView = target.getUseIn();
		List<MethodNode> fieldView = field.getUseIn();
		List<MethodNode> classMethodView = owner.getUseInMth();
		List<ClassNode> classView = owner.getUseIn();
		Set<MethodNode> usedView = left.getUsed();
		inputMethods.clear();
		inputClasses.clear();
		assertSoftly(soft -> {
			soft.assertThat(target.getUseIn()).as("method setter detaches input").containsExactly(left);
			soft.assertThat(field.getUseIn()).as("field setter detaches input").containsExactly(left);
			soft.assertThat(owner.getUseInMth()).as("class method setter detaches input").containsExactly(left);
			soft.assertThat(owner.getUseIn()).as("class setter detaches input").containsExactly(left.getParentClass());

			target.setUseIn(List.of(left, right));
			field.addUseIn(right);
			owner.setUseInMth(List.of(left, right));
			owner.setUseIn(List.of(left.getParentClass(), right.getParentClass()));
			bridge.setUseIn(List.of(left));
			soft.assertThat(methodView).as("previous method view").containsExactly(left);
			soft.assertThat(fieldView).as("previous field view").containsExactly(left);
			soft.assertThat(classMethodView).as("previous class method view").containsExactly(left);
			soft.assertThat(classView).as("previous class view").containsExactly(left.getParentClass());
			soft.assertThat(usedView).as("previous reverse usage view").containsExactly(target);
			soft.assertThat(field.getUseIn()).as("current field callers").containsExactly(left, right);
			soft.assertThat(left.getUsed()).as("current reverse usage").containsExactlyInAnyOrder(target, bridge);
		});
	}

	@Test
	void replacingMethodUsageHandlesEmptyAndOverlappingSnapshots() {
		RootNode root = loadFixture();
		MethodNode target = getMethod(root.resolveClass("inline.concurrent.Target"), "target");
		MethodNode source = getMethod(root.resolveClass("inline.concurrent.Bridge"), "bridge");
		MethodNode left = getMethod(root.resolveClass("inline.concurrent.Left"), "call");
		MethodNode right = getMethod(root.resolveClass("inline.concurrent.Right"), "call");

		target.setUseIn(List.of());
		source.setUseIn(List.of(left, right));
		List<MethodNode> sourceView = source.getUseIn();
		UsageInfoVisitor.replaceMethodUsage(target, source);
		assertThat(target.getUseIn()).containsExactly(left, right);
		assertThat(source.getUseIn()).isEmpty();
		assertThat(sourceView).containsExactly(left, right);

		target.setUseIn(List.of(source, left));
		source.setUseIn(List.of());
		UsageInfoVisitor.replaceMethodUsage(target, source);
		assertThat(target.getUseIn()).containsExactly(left);
		assertThat(source.getUseIn()).isEmpty();

		target.setUseIn(List.of(right, source));
		source.setUseIn(List.of(left, right));
		UsageInfoVisitor.replaceMethodUsage(target, source);
		assertThat(target.getUseIn()).containsExactly(left, right);
		assertThat(source.getUseIn()).isEmpty();
		assertThat(left.getUsed()).containsExactly(target);
		assertThat(right.getUsed()).containsExactly(target);
		assertThat(source.getUsed()).isEmpty();
	}

	private RootNode loadFixture() {
		File dir = new File("src/test/smali/inline/TestConcurrentMethodInline");
		jadxDecompiler = loadFiles(List.of(new File(dir, "Bridge.smali"), new File(dir, "Target.smali"),
				new File(dir, "Left.smali"), new File(dir, "Right.smali")));
		return jadxDecompiler.getRoot();
	}

	private void checkConcurrentInlining(GatePoint gatePoint) throws Exception {
		RootNode root = loadFixture();
		ClassNode bridgeClass = root.resolveClass("inline.concurrent.Bridge");
		ClassNode targetClass = root.resolveClass("inline.concurrent.Target");
		MethodNode bridge = getMethod(bridgeClass, "bridge");
		MethodNode target = getMethod(targetClass, "target");
		FieldNode field = getField(targetClass, "value");
		List<MethodNode> callers = List.of(getMethod(root.resolveClass("inline.concurrent.Left"), "call"),
				getMethod(root.resolveClass("inline.concurrent.Right"), "call"));

		bridge.load();
		InsnNode replacement;
		if (gatePoint == GatePoint.FIELD) {
			replacement = new IndexInsnNode(InsnType.SPUT, field.getFieldInfo(), 1);
			replacement.addArg(InsnArg.lit(7, ArgType.INT));
		} else {
			replacement = new InvokeNode(target.getMethodInfo(), InvokeType.STATIC, 0);
			replacement.addAttr(target);
		}
		// Register-free IR keeps the real visitor focused on its shared usage updates.
		setBody(bridge, replacement);
		MethodInlineAttr.markForInline(bridge, replacement);
		for (MethodNode caller : callers) {
			caller.load();
			InvokeNode invoke = new InvokeNode(bridge.getMethodInfo(), InvokeType.STATIC, 0);
			invoke.addAttr(bridge);
			setBody(caller, invoke);
		}

		GatedList<MethodNode> bridgeUsers = new GatedList<>(callers, true);
		GatedList<MethodNode> targetUsers = new GatedList<>(List.of(bridge), true);
		GatedList<MethodNode> classMethodUsers = new GatedList<>(List.of(bridge), false);
		GatedList<ClassNode> classUsers = new GatedList<>(List.of(bridgeClass), false);
		bridge.setUseIn(bridgeUsers);
		if (gatePoint == GatePoint.FIELD) {
			target.setUseIn(List.of());
			field.setUseIn(targetUsers);
		} else {
			target.setUseIn(targetUsers);
		}
		targetClass.setUseInMth(classMethodUsers);
		targetClass.setUseIn(classUsers);
		// Snapshotting setters must detach these lists before the gates are armed.
		switch (gatePoint) {
			case BRIDGE:
				bridgeUsers.arm();
				break;
			case METHOD:
			case FIELD:
				targetUsers.arm();
				break;
			case CLASS:
				classMethodUsers.arm();
				classUsers.arm();
				break;
		}

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<?>> tasks = new ArrayList<>();
			for (MethodNode caller : callers) {
				tasks.add(executor.submit(() -> {
					assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
					synchronized (caller.getTopParentClass().getClassInfo()) {
						new InlineMethods().visit(caller);
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> task : tasks) {
				task.get(10, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}

		assertSoftly(soft -> {
			soft.assertThat(bridge.getUseIn()).as("inlined bridge callers").isEmpty();
			soft.assertThat(gatePoint == GatePoint.FIELD ? field.getUseIn() : target.getUseIn())
					.as("target member callers").containsExactlyInAnyOrderElementsOf(callers);
			soft.assertThat(targetClass.getUseInMth()).as("target class method callers").containsExactlyInAnyOrderElementsOf(callers);
			soft.assertThat(targetClass.getUseIn()).as("target class callers")
					.containsExactlyInAnyOrder(callers.get(0).getParentClass(), callers.get(1).getParentClass());
			soft.assertThat(bridge.getUsed()).as("inlined bridge reverse usage").isEmpty();
			for (MethodNode caller : callers) {
				if (gatePoint == GatePoint.FIELD) {
					soft.assertThat(caller.getUsed()).as("field caller reverse usage: %s", caller).isEmpty();
				} else {
					soft.assertThat(caller.getUsed()).as("method caller reverse usage: %s", caller).containsExactly(target);
				}
			}
		});
		for (MethodNode caller : callers) {
			InsnNode inlined = caller.getBasicBlocks().get(0).getInstructions().get(0);
			if (gatePoint == GatePoint.FIELD) {
				assertThat(inlined.getType()).isEqualTo(InsnType.SPUT);
				assertThat(((IndexInsnNode) inlined).getIndex()).isEqualTo(field.getFieldInfo());
			} else {
				assertThat(((InvokeNode) inlined).getCallMth()).isEqualTo(target.getMethodInfo());
			}
		}
	}

	private static void setBody(MethodNode mth, InsnNode insn) {
		BlockNode block = new BlockNode(0, 0, 0);
		block.getInstructions().add(insn);
		block.getInstructions().add(new InsnNode(InsnType.RETURN, 0));
		mth.setBasicBlocks(List.of(block));
	}

	@SuppressWarnings("checkstyle:IllegalType") // Gate ArrayList operations used by the old implementation.
	private static final class GatedList<T> extends ArrayList<T> {
		private final boolean gateCopies;
		private final CyclicBarrier barrier = new CyclicBarrier(2);
		private final AtomicInteger visits = new AtomicInteger();
		private volatile boolean armed;

		private GatedList(List<T> values, boolean gateCopies) {
			super(values);
			this.gateCopies = gateCopies;
		}

		private void arm() {
			armed = true;
		}

		@Override
		public Object[] toArray() {
			Object[] snapshot = super.toArray();
			if (gateCopies) {
				awaitPeer();
			}
			return snapshot;
		}

		@Override
		public int indexOf(Object value) {
			int index = super.indexOf(value);
			if (!gateCopies) {
				awaitPeer();
			}
			return index;
		}

		private void awaitPeer() {
			if (armed && visits.getAndIncrement() < 2) {
				try {
					barrier.await(5, TimeUnit.SECONDS);
				} catch (Exception e) {
					throw new AssertionError("Both inline visitors must reach the shared usage list", e);
				}
			}
		}
	}
}
