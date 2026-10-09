package jadx.core.dex.visitors.typeinference;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.dex.instructions.args.ArgType;
import jadx.tests.api.utils.TestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class TypeSearchTest {
	@Test
	void testLazyCandidatesKeepOrderAndStoppingCondition() {
		JadxArgs args = new JadxArgs();
		args.addInputFile(TestUtils.getFileForSample("test-samples/hello.dex"));
		try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
			decompiler.load();
			TypeSearch search = new TypeSearch(decompiler.getRoot().getClasses().get(0).getMethods().get(0));
			List<ArgType> candidates = IntStream.range(0, 20)
					.mapToObj(i -> ArgType.object("test.Type" + i))
					.collect(Collectors.toList());
			Set<ArgType> collected = new LinkedHashSet<>();
			AtomicInteger inspected = new AtomicInteger();
			Stream<ArgType> stream = Stream.concat(Stream.of(ArgType.UNKNOWN), candidates.stream())
					.peek(type -> inspected.incrementAndGet());
			search.addCandidateTypes(Collections.emptySet(), collected, stream);
			assertThat(collected).containsExactlyElementsOf(candidates.subList(0, 11));
			assertThat(inspected.get()).isEqualTo(12);

			ArgType extra = ArgType.object("test.Extra");
			inspected.set(0);
			search.addCandidateTypes(Collections.emptySet(), collected,
					Stream.of(ArgType.UNKNOWN, candidates.get(0), extra).peek(type -> inspected.incrementAndGet()));
			assertThat(collected).containsExactlyElementsOf(candidates.subList(0, 11));
			assertThat(inspected.get()).isEqualTo(2);

			inspected.set(0);
			search.addCandidateTypes(Collections.emptySet(), collected,
					Stream.of(ArgType.UNKNOWN, extra, ArgType.STRING).peek(type -> inspected.incrementAndGet()));
			assertThat(collected).hasSize(12).contains(extra).doesNotContain(ArgType.STRING);
			assertThat(inspected.get()).isEqualTo(2);
		}
	}
}
