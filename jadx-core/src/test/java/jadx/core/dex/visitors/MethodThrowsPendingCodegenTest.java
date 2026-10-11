package jadx.core.dex.visitors;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import jadx.api.CommentsLevel;
import jadx.api.JadxInternalAccess;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.JadxCommentsAttr;
import jadx.core.dex.attributes.nodes.MethodThrowsAttr;
import jadx.core.dex.attributes.nodes.MethodThrowsAttr.Call;
import jadx.core.dex.attributes.nodes.MethodThrowsAttr.PendingInfo;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.ProcessState;
import jadx.core.dex.nodes.RootNode;
import jadx.core.utils.InsnRemover;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;

class MethodThrowsPendingCodegenTest extends SmaliTest {

	@Test
	void refreshPreparedCallerWhenForeignThrowsBecomeReady() {
		assertRefreshed("ordinary()V", "ordinary() throws FileNotFoundException", "java.io.FileNotFoundException", false);
	}

	@Test
	void refreshUsesRecordedCallAfterItsInstructionIsWrapped() {
		assertRefreshed("wrappedReturn()I", "wrappedReturn() throws FileNotFoundException", "java.io.FileNotFoundException", true);
	}

	@Test
	void refreshKeepsCaughtForeignExceptionExcluded() {
		assertRefreshed("caught()V", "caught() {", null, false);
	}

	@Test
	void refreshKeepsExplicitExceptionRethrow() {
		assertRefreshed("rethrow()V", "rethrow() throws IOException", "java.io.IOException", false);
	}

	@Test
	void refreshClosesForeignPendingCycleWithoutChangingForeignAttributes() {
		RootNode root = loadMetadataFixture();
		ClassNode caller = root.resolveClass("others.TestMethodThrowsLateCaller");
		MethodNode method = caller.searchMethodByShortId("ordinary()V");
		MethodNode source = root.resolveClass("others.TestMethodThrowsLateSource").searchMethodByShortId("fail()V");
		String unknown = "missing.UnknownException";
		assertThat(root.getClsp().isClsKnown(unknown)).isFalse();
		MethodThrowsAttr oldCaller = pending(method, Collections.singleton(unknown), source);
		MethodThrowsAttr oldSource = pending(source, Collections.singleton("java.io.FileNotFoundException"), method);
		source.addInfoComment("existing foreign comment");
		JadxCommentsAttr sourceComments = source.get(AType.JADX_COMMENTS);

		refreshThrows(caller);

		MethodThrowsAttr completed = method.get(AType.METHOD_THROWS);
		assertThat(completed.isVisited()).isTrue();
		assertThat(completed.getList()).containsExactlyInAnyOrder(unknown, "java.io.FileNotFoundException");
		assertThat(method.get(AType.JADX_COMMENTS).getComments().get(CommentsLevel.INFO))
				.contains("Thrown type has an unknown type hierarchy: " + unknown);
		assertThat(oldCaller.isVisited()).isFalse();
		assertThat(oldCaller.getList()).containsExactly(unknown);
		assertThat(source.get(AType.METHOD_THROWS)).isSameAs(oldSource);
		assertThat(oldSource.isVisited()).isFalse();
		assertThat(oldSource.getList()).containsExactly("java.io.FileNotFoundException");
		assertThat(source.get(AType.JADX_COMMENTS)).isSameAs(sourceComments);
		assertThat(sourceComments.getComments()).containsOnlyKeys(CommentsLevel.INFO);
		assertThat(sourceComments.getComments().get(CommentsLevel.INFO)).containsExactly("existing foreign comment");
		assertThat(source.getBasicBlocks()).isNull();
	}

	@Test
	void refreshKeepsMissingAndIncompleteFactsPending() {
		RootNode root = loadMetadataFixture();
		ClassNode caller = root.resolveClass("others.TestMethodThrowsLateCaller");
		MethodNode method = caller.searchMethodByShortId("ordinary()V");
		MethodNode dependent = caller.searchMethodByShortId("wrappedReturn()I");
		MethodNode source = root.resolveClass("others.TestMethodThrowsLateSource").searchMethodByShortId("fail()V");
		Set<String> ownThrows = Collections.singleton("java.io.IOException");
		PendingInfo incomplete = new PendingInfo(ownThrows,
				Collections.singletonList(new Call(source.getMethodInfo(), Collections.emptySet())), false);
		for (PendingInfo facts : Arrays.asList(null, incomplete)) {
			MethodThrowsAttr unscanned = new MethodThrowsAttr(new HashSet<>(ownThrows), facts);
			method.addAttr(unscanned);
			pending(dependent, Collections.emptySet(), method);

			refreshThrows(caller);

			assertThat(method.get(AType.METHOD_THROWS)).isSameAs(unscanned);
			assertThat(unscanned.isVisited()).isFalse();
			assertThat(unscanned.getList()).containsExactly("java.io.IOException");
			MethodThrowsAttr refreshed = dependent.get(AType.METHOD_THROWS);
			assertThat(refreshed.isVisited()).isFalse();
			assertThat(refreshed.getPendingInfo()).isNotNull();
			assertThat(refreshed.getList()).containsExactly("java.io.IOException");
		}
	}

	@Test
	void refreshResolvesReplacementMethodAfterForeignDeepUnload() {
		RootNode root = loadMetadataFixture();
		ClassNode caller = root.resolveClass("others.TestMethodThrowsLateCaller");
		MethodNode method = caller.searchMethodByShortId("ordinary()V");
		ClassNode sourceClass = root.resolveClass("others.TestMethodThrowsLateSource");
		MethodNode oldSource = sourceClass.searchMethodByShortId("fail()V");
		MethodThrowsAttr oldSourceThrows = completed(Collections.singleton("java.io.IOException"));
		oldSource.addAttr(oldSourceThrows);
		pending(method, Collections.emptySet(), oldSource);

		sourceClass.deepUnload();
		MethodNode replacement = sourceClass.searchMethodByShortId("fail()V");
		assertThat(replacement).isNotSameAs(oldSource);
		assertThat(replacement.getMethodInfo()).isEqualTo(oldSource.getMethodInfo());
		assertThat(root.resolveMethod(oldSource.getMethodInfo())).isSameAs(replacement);
		assertThat(replacement.get(AType.METHOD_THROWS)).isNull();
		refreshThrows(caller);
		assertThat(method.get(AType.METHOD_THROWS).isVisited()).isFalse();
		assertThat(method.get(AType.METHOD_THROWS).getList()).isEmpty();

		MethodThrowsAttr replacementThrows = completed(Collections.singleton("java.io.FileNotFoundException"));
		replacement.addAttr(replacementThrows);
		refreshThrows(caller);
		assertThat(method.get(AType.METHOD_THROWS).isVisited()).isTrue();
		assertThat(method.get(AType.METHOD_THROWS).getList()).containsExactly("java.io.FileNotFoundException");
		assertThat(replacement.get(AType.METHOD_THROWS)).isSameAs(replacementThrows);
		assertThat(oldSource.get(AType.METHOD_THROWS)).isSameAs(oldSourceThrows);
		assertThat(oldSourceThrows.getList()).containsExactly("java.io.IOException");
	}

	private void assertRefreshed(String methodId, String declaration, String exception, boolean wrapCall) {
		RootNode root = loadFixture();
		ClassNode caller = root.resolveClass("others.TestMethodThrowsLateCaller");
		ClassNode source = root.resolveClass("others.TestMethodThrowsLateSource");
		MethodNode method = caller.searchMethodByShortId(methodId);
		caller.add(AFlag.DONT_UNLOAD_CLASS);
		caller.load();
		if (wrapCall) {
			// Keep this call visible at the original throws pass, then model a later wrapping pass.
			for (InsnNode insn : method.getInstructions()) {
				if (insn != null && insn.getType() == InsnType.INVOKE) {
					insn.add(AFlag.DONT_INLINE);
				}
			}
		}
		root.getProcessClasses().forceProcess(caller);
		assertThat(caller.getState()).isEqualTo(ProcessState.PROCESS_COMPLETE);
		assertThat(source.searchMethodByShortId("fail()V").getBasicBlocks()).isNull();
		MethodThrowsAttr pending = method.get(AType.METHOD_THROWS);
		assertThat(pending).isNotNull();
		assertThat(pending.isVisited()).isFalse();
		if (methodId.equals("rethrow()V")) {
			assertThat(pending.getList()).containsExactly("java.io.IOException");
		} else {
			assertThat(pending.getList()).isEmpty();
		}
		List<BlockNode> blocks = method.getBasicBlocks();
		if (wrapCall) {
			wrapReturnCall(method);
		}
		root.getProcessClasses().forceProcess(source);
		MethodThrowsAttr sourceThrows = source.searchMethodByShortId(wrapCall ? "value()I" : "fail()V").get(AType.METHOD_THROWS);
		assertThat(sourceThrows.isVisited()).isTrue();
		assertThat(sourceThrows.getList()).containsExactly("java.io.FileNotFoundException");
		assertThat(method.get(AType.METHOD_THROWS)).isSameAs(pending);
		assertThat(caller.getCode().getCodeStr()).contains(declaration);
		assertThat(method.getBasicBlocks()).isSameAs(blocks);
		MethodThrowsAttr completed = method.get(AType.METHOD_THROWS);
		assertThat(completed).isNotSameAs(pending);
		assertThat(completed.isVisited()).isTrue();
		assertThat(pending.isVisited()).isFalse();
		assertThat(pending.getList()).containsExactly(methodId.equals("rethrow()V")
				? new String[] { "java.io.IOException" }
				: new String[0]);
		if (exception == null) {
			assertThat(completed.getList()).isEmpty();
		} else {
			assertThat(completed.getList()).containsExactly(exception);
		}
	}

	private RootNode loadFixture() {
		jadxDecompiler = loadFiles(Arrays.asList(
				new File("src/test/smali/others/TestMethodThrowsLateCaller.smali"),
				new File("src/test/smali/others/TestMethodThrowsLateSource.smali")));
		return JadxInternalAccess.getRoot(jadxDecompiler);
	}

	private RootNode loadMetadataFixture() {
		RootNode root = loadFixture();
		for (ClassNode cls : root.getClasses()) {
			for (MethodNode method : cls.getMethods()) {
				method.addAttr(completed(Collections.emptySet()));
			}
		}
		return root;
	}

	private static MethodThrowsAttr completed(Set<String> exceptions) {
		MethodThrowsAttr attr = new MethodThrowsAttr(new HashSet<>(exceptions));
		attr.setVisited(true);
		return attr;
	}

	private static MethodThrowsAttr pending(MethodNode method, Set<String> ownThrows, MethodNode callee) {
		PendingInfo facts = new PendingInfo(ownThrows,
				Collections.singletonList(new Call(callee.getMethodInfo(), Collections.emptySet())), true);
		MethodThrowsAttr attr = new MethodThrowsAttr(new HashSet<>(ownThrows), facts);
		method.addAttr(attr);
		return attr;
	}

	private static void refreshThrows(ClassNode cls) {
		synchronized (cls.getClassInfo()) {
			MethodThrowsVisitor.refreshPendingThrows(cls);
		}
	}

	private static void wrapReturnCall(MethodNode method) {
		InsnNode invoke = null;
		InsnNode ret = null;
		BlockNode invokeBlock = null;
		for (BlockNode block : method.getBasicBlocks()) {
			for (InsnNode insn : block.getInstructions()) {
				if (insn.getType() == InsnType.INVOKE) {
					invoke = insn;
					invokeBlock = block;
				} else if (insn.getType() == InsnType.RETURN) {
					ret = insn;
				}
			}
		}
		assertThat(invoke).isNotNull();
		assertThat(ret).isNotNull();
		invoke.remove(AFlag.DONT_INLINE);
		assertThat(ret.getArg(0).wrapInstruction(method, invoke, false)).isNotNull();
		InsnRemover.unbindResult(method, invoke);
		assertThat(InsnRemover.removeWithoutUnbind(method, invokeBlock, invoke)).isTrue();
		assertThat(ret.getArg(0).isInsnWrap()).isTrue();
	}
}
