package jadx.tests.integration.generics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import jadx.tests.api.SmaliTest;

import static jadx.tests.api.utils.assertj.JadxAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TestClassAsSubclassCatch extends SmaliTest {

	@Test
	public void testTypesAndFactoryFailures() {
		assertThat(getClassNodeFromSmali())
				.runDecompiledAutoCheck(this)
				.code()
				.containsOne(".asSubclass(Runnable.class)")
				.doesNotContain("extends U");
	}

	public static void check(Class<?> cls) throws Exception {
		Method create = cls.getMethod("create", String.class);
		assertThat(create.invoke(null, Thread.class.getName())).isInstanceOf(Thread.class);

		InvocationTargetException missingClass = assertThrows(InvocationTargetException.class,
				() -> create.invoke(null, "generics.MissingRunnable"));
		assertThat(missingClass.getCause()).isInstanceOf(IllegalArgumentException.class)
				.hasCauseInstanceOf(ClassNotFoundException.class);

		InvocationTargetException wrongType = assertThrows(InvocationTargetException.class,
				() -> create.invoke(null, String.class.getName()));
		assertThat(wrongType.getCause()).isInstanceOf(IllegalArgumentException.class)
				.hasCauseInstanceOf(ClassCastException.class);
	}
}
