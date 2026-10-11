package jadx.tests.integration.generics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.Test;

import jadx.tests.api.SmaliTest;

import static jadx.tests.api.utils.assertj.JadxAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TestGenericCatchBoundary extends SmaliTest {

	@Test
	public void testTypesAndCatchBoundary() {
		assertThat(getClassNodeFromSmaliFiles())
				.runDecompiledAutoCheck(this)
				.code()
				.containsOne("Iterator<? extends R>")
				.containsOne("GenericSink<? super R>")
				.containsOne(".onNext(")
				.containsOne(".onError(");
	}

	public static void check(Object instance) throws Exception {
		Class<?> sinkClass = instance.getClass().getClassLoader().loadClass("generics.GenericSink");
		Method drain = instance.getClass().getMethod("drain", Iterator.class, sinkClass);
		List<Object> values = new ArrayList<>();
		List<Throwable> errors = new ArrayList<>();
		Object sink = Proxy.newProxyInstance(sinkClass.getClassLoader(), new Class<?>[] { sinkClass }, (proxy, method, args) -> {
			if (method.getName().equals("onNext")) {
				values.add(args[0]);
			} else if (method.getName().equals("onError")) {
				errors.add((Throwable) args[0]);
			}
			return null;
		});
		drain.invoke(instance, Collections.singletonList("value").iterator(), sink);
		assertThat(values).containsExactly("value");
		assertThat(errors).isEmpty();

		RuntimeException producerFailure = new RuntimeException("producer");
		Iterator<Object> failingIterator = new Iterator<Object>() {
			@Override
			public boolean hasNext() {
				return true;
			}

			@Override
			public Object next() {
				throw producerFailure;
			}
		};
		drain.invoke(instance, failingIterator, sink);
		assertThat(values).containsExactly("value");
		assertThat(errors).containsExactly(producerFailure);

		errors.clear();
		drain.invoke(instance, Collections.singletonList(null).iterator(), sink);
		assertThat(values).containsExactly("value");
		assertThat(errors).singleElement().isInstanceOf(NullPointerException.class);

		RuntimeException consumerFailure = new RuntimeException("consumer");
		errors.clear();
		Object failingSink = Proxy.newProxyInstance(sinkClass.getClassLoader(), new Class<?>[] { sinkClass }, (proxy, method, args) -> {
			if (method.getName().equals("onNext")) {
				throw consumerFailure;
			}
			errors.add((Throwable) args[0]);
			return null;
		});
		InvocationTargetException failure = assertThrows(InvocationTargetException.class,
				() -> drain.invoke(instance, Collections.singletonList("value").iterator(), failingSink));
		assertThat(failure.getCause()).isSameAs(consumerFailure);
		assertThat(errors).isEmpty();
	}
}
