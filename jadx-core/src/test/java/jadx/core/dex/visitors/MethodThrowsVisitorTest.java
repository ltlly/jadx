package jadx.core.dex.visitors;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import jadx.api.JadxInternalAccess;
import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.MethodThrowsAttr;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.ProcessState;
import jadx.core.dex.nodes.RootNode;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;

class MethodThrowsVisitorTest extends SmaliTest {

	@Test
	void unloadedMethodCanBeAnalyzedAfterLoading() throws Exception {
		ClassNode cls = loadTestClass();
		MethodNode source = cls.searchMethodByShortId("exceptionSource()V");
		newVisitor(cls.root()).visit(source);
		MethodThrowsAttr attr = source.get(AType.METHOD_THROWS);
		assertThat(attr == null || !attr.isVisited()).isTrue();
		assertThat(cls.getCode().getCodeStr()).contains("exceptionSource() throws FileNotFoundException");
	}

	@Test
	void preparedCallerDoesNotPoisonUnloadedCallee() throws Exception {
		ClassNode cls = loadTestClass();
		cls.load();
		MethodNode caller = cls.searchMethodByShortId("missingThrowsAnnotation()V");
		assertThat(cls.root().getProcessClasses()
				.processMethodUntilVisitor(caller, "MethodThrowsVisitor", false)).isTrue();
		newVisitor(cls.root()).visit(caller);
		cls.unload();
		assertThat(cls.getCode().getCodeStr())
				.contains("exceptionSource() throws FileNotFoundException")
				.contains("missingThrowsAnnotation() throws FileNotFoundException");
	}

	@Test
	void pendingCalleeKeepsWholeCyclePending() throws Exception {
		ClassNode cls = loadTestClass();
		cls.load();
		MethodNode first = cls.searchMethodByShortId("doSomething1(I)V");
		MethodNode second = cls.searchMethodByShortId("doSomething2(I)V");
		assertThat(cls.root().getProcessClasses()
				.processMethodUntilVisitor(first, "MethodThrowsVisitor", false)).isTrue();
		assertThat(cls.root().getProcessClasses()
				.processMethodUntilVisitor(second, "MethodThrowsVisitor", false)).isTrue();
		newVisitor(cls.root()).visit(first);
		assertThat(first.get(AType.METHOD_THROWS).isVisited()).isFalse();
		assertThat(second.get(AType.METHOD_THROWS).isVisited()).isFalse();
		cls.unload();
		assertThat(cls.getCode().getCodeStr())
				.contains("doSomething1(int i) throws FileNotFoundException")
				.contains("doSomething2(int i) throws FileNotFoundException");
	}

	@Test
	void propagateExceptionsAroundReadyCycle() {
		jadxDecompiler = loadFiles(Collections.singletonList(new File("src/test/smali/others/TestMethodThrowsCycle.smali")));
		ClassNode cls = JadxInternalAccess.getRoot(jadxDecompiler).resolveClass("others.TestMethodThrowsCycle");
		assertThat(cls.getCode().getCodeStr())
				.contains("first() throws FileNotFoundException")
				.contains("second() throws FileNotFoundException");
	}

	@Test
	void inferExceptionsFromForeignDependency() {
		jadxDecompiler = loadFiles(Arrays.asList(
				new File("src/test/smali/others/TestMissingExceptions.smali"),
				new File("src/test/smali/others/TestMethodThrowsForeign.smali")));
		ClassNode cls = JadxInternalAccess.getRoot(jadxDecompiler).resolveClass("others.TestMethodThrowsForeign");
		assertThat(cls.getCode().getCodeStr())
				.contains("test(TestMissingExceptions source) throws FileNotFoundException");
	}

	@Test
	void propagateKnownExceptionsFromPendingForeignDependency() {
		jadxDecompiler = loadFiles(Arrays.asList(
				new File("src/test/smali/others/TestMethodThrowsPendingCaller.smali"),
				new File("src/test/smali/others/TestMethodThrowsPendingSource.smali"),
				new File("src/test/smali/others/TestMethodThrowsPendingHelper.smali")));
		RootNode root = JadxInternalAccess.getRoot(jadxDecompiler);
		ClassNode caller = root.resolveClass("others.TestMethodThrowsPendingCaller");
		ClassNode source = root.resolveClass("others.TestMethodThrowsPendingSource");
		MethodNode sourceMethod = source.searchMethodByShortId("call()V");
		MethodNode helper = root.resolveClass("others.TestMethodThrowsPendingHelper").searchMethodByShortId("noop()V");
		String code = caller.getCode().getCodeStr();
		assertThat(helper.getBasicBlocks()).isNull();
		assertThat(sourceMethod.get(JadxAttrType.EXCEPTIONS)).isNull();
		MethodThrowsAttr partial = sourceMethod.get(AType.METHOD_THROWS);
		assertThat(partial.isVisited()).isFalse();
		assertThat(partial.getList()).containsExactly("java.io.FileNotFoundException");
		assertThat(code).contains("test() throws FileNotFoundException");
		assertThat(caller.searchMethodByShortId("test()V").get(AType.METHOD_THROWS).isVisited()).isFalse();

		// A later complete analysis replaces the publication without changing the old set.
		source.unload();
		source.getCode();
		MethodThrowsAttr completed = sourceMethod.get(AType.METHOD_THROWS);
		assertThat(completed).isNotSameAs(partial);
		assertThat(completed.isVisited()).isTrue();
		assertThat(completed.getList()).containsExactly("java.io.FileNotFoundException");
		assertThat(partial.isVisited()).isFalse();
		assertThat(partial.getList()).containsExactly("java.io.FileNotFoundException");
	}

	@Test
	void publishedThrowsSurviveForeignClassReentry() throws Exception {
		jadxDecompiler = loadFiles(Arrays.asList(
				new File("src/test/smali/others/TestMissingExceptions.smali"),
				new File("src/test/smali/others/TestMethodThrowsForeign.smali")));
		RootNode root = JadxInternalAccess.getRoot(jadxDecompiler);
		ClassNode sourceClass = root.resolveClass("others.TestMissingExceptions");
		MethodNode source = sourceClass.searchMethodByShortId("missingThrowsAnnotation()V");
		sourceClass.getCode();
		MethodThrowsAttr completed = source.get(AType.METHOD_THROWS);
		assertThat(completed.isVisited()).isTrue();
		assertThat(completed.getList()).containsExactly("java.io.FileNotFoundException");
		assertThat(source.get(JadxAttrType.EXCEPTIONS)).isNull();

		ClassNode callerClass = root.resolveClass("others.TestMethodThrowsForeign");
		MethodNode caller = callerClass.searchMethodByShortId("test(Lothers/TestMissingExceptions;)V");
		callerClass.load();
		assertThat(root.getProcessClasses().processMethodUntilVisitor(caller, "MethodThrowsVisitor", false)).isTrue();
		for (ProcessState state : Arrays.asList(ProcessState.NOT_LOADED, ProcessState.PROCESS_STARTED)) {
			caller.remove(AType.METHOD_THROWS); // Require a fresh caller analysis for each callee state.
			sourceClass.unload();
			if (state == ProcessState.PROCESS_STARTED) {
				sourceClass.load();
				sourceClass.setState(ProcessState.PROCESS_STARTED);
			}
			assertThat(sourceClass.getState()).isEqualTo(state);
			assertThat(source.get(AType.METHOD_THROWS)).isSameAs(completed);
			newVisitor(root).visit(caller);
			MethodThrowsAttr callerThrows = caller.get(AType.METHOD_THROWS);
			assertThat(callerThrows.getList()).as("callee state %s", state).containsExactly("java.io.FileNotFoundException");
			assertThat(callerThrows.isVisited()).isTrue();
		}
	}

	private ClassNode loadTestClass() {
		jadxDecompiler = loadFiles(Collections.singletonList(new File("src/test/smali/others/TestMissingExceptions.smali")));
		return JadxInternalAccess.getRoot(jadxDecompiler).resolveClass("others.TestMissingExceptions");
	}

	private static MethodThrowsVisitor newVisitor(RootNode root) throws Exception {
		MethodThrowsVisitor visitor = new MethodThrowsVisitor();
		visitor.init(root);
		return visitor;
	}
}
