package jadx.core.dex.nodes.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jadx.api.JadxArgs;
import jadx.core.clsp.ClspClass;
import jadx.core.clsp.ClspClassSource;
import jadx.core.clsp.ClspMethod;
import jadx.core.dex.info.ClassInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.IMethodDetails;
import jadx.core.dex.nodes.RootNode;

import static org.assertj.core.api.Assertions.assertThat;

class MethodUtilsTest {

	private RootNode root;
	private MethodUtils methodUtils;

	@BeforeEach
	public void init() {
		root = new RootNode(new JadxArgs());
		root.loadClasses(Collections.emptyList());
		root.initClassPath();
		methodUtils = new MethodUtils(root);
	}

	@Test
	void isMethodArgsOverloadedOnExternalClass() {
		MethodInfo valueOf = mthInfo("java.lang.String", "valueOf", ArgType.INT, ArgType.INT);
		assertThat(methodUtils.isMethodArgsOverloaded(ArgType.object("java.lang.String"), valueOf)).isTrue();
		// second call is served from the index and must return the same result
		assertThat(methodUtils.isMethodArgsOverloaded(ArgType.object("java.lang.String"), valueOf)).isTrue();

		// same name and args count, but a unique method: no overload except itself
		MethodInfo charAt = mthInfo("java.lang.String", "charAt", ArgType.INT, ArgType.CHAR);
		assertThat(methodUtils.isMethodArgsOverloaded(ArgType.object("java.lang.String"), charAt)).isFalse();
		assertThat(methodUtils.isMethodArgsOverloaded(ArgType.object("java.lang.String"), charAt)).isFalse();
	}

	@Test
	void collectOverloadedMethodsIsStableAcrossCacheHits() {
		ArgType startCls = ArgType.object("java.lang.StringBuilder");
		MethodInfo append = mthInfo("java.lang.StringBuilder", "append", ArgType.INT,
				ArgType.object("java.lang.StringBuilder"));
		List<IMethodDetails> first = methodUtils.collectOverloadedMethods(startCls, append);
		List<IMethodDetails> second = methodUtils.collectOverloadedMethods(startCls, append);

		assertThat(first).isNotEmpty();
		assertThat(second).containsExactlyElementsOf(first);
		for (IMethodDetails details : first) {
			assertThat(details.getMethodInfo().getName()).isEqualTo("append");
			assertThat(details.getMethodInfo().getArgsCount()).isEqualTo(1);
		}
	}

	@Test
	void overloadIndexMatchesLinearScan() {
		ClspClass cls = new ClspClass(ArgType.object("test.ExternalCls"), 1, 0, ClspClassSource.CORE);
		cls.setMethods(List.of(
				clspMethod("test.ExternalCls", "foo", ArgType.INT, ArgType.VOID),
				clspMethod("test.ExternalCls", "foo", ArgType.LONG, ArgType.VOID),
				clspMethod("test.ExternalCls", "foo", ArgType.INT, ArgType.BOOLEAN),
				clspMethod("test.ExternalCls", "bar", ArgType.INT, ArgType.VOID),
				clspMethod("test.ExternalCls", "<init>", ArgType.INT, ArgType.VOID),
				clspMethod("test.ExternalCls", "<init>", ArgType.LONG, ArgType.VOID),
				clspMethod("test.ExternalCls", "<clinit>", null, ArgType.VOID)));

		Map<MethodUtils.OverloadKey, List<ClspMethod>> index = MethodUtils.buildOverloadIndex(cls);
		for (ClspMethod mth : cls.getMethodsMap().values()) {
			MethodInfo mthInfo = mth.getMethodInfo();
			MethodUtils.OverloadKey key = new MethodUtils.OverloadKey(mthInfo.getName(), mthInfo.getArgsCount());
			// bucket = all methods with same name and args count, in the original encounter order
			List<ClspMethod> expectedBucket = new ArrayList<>();
			for (ClspMethod other : cls.getMethodsMap().values()) {
				if (mthInfo.getName().equals(other.getMethodInfo().getName())
						&& mthInfo.getArgsCount() == other.getMethodInfo().getArgsCount()) {
					expectedBucket.add(other);
				}
			}
			assertThat(index.get(key)).containsExactlyElementsOf(expectedBucket);
			// short id filtering on the bucket gives the same result as the uncached linear scan
			List<ClspMethod> viaIndex = new ArrayList<>();
			for (ClspMethod candidate : index.get(key)) {
				if (mthInfo.isOverloadedBy(candidate.getMethodInfo())) {
					viaIndex.add(candidate);
				}
			}
			List<ClspMethod> linear = new ArrayList<>();
			for (ClspMethod other : cls.getMethodsMap().values()) {
				if (mthInfo.isOverloadedBy(other.getMethodInfo())) {
					linear.add(other);
				}
			}
			assertThat(viaIndex).containsExactlyElementsOf(linear);
		}
		// same name and args count, different signatures are real overloads
		assertThat(index.get(new MethodUtils.OverloadKey("foo", 1))).hasSize(3);
		// constructors are indexed per class, clinit is included as any other method
		assertThat(index.get(new MethodUtils.OverloadKey("<init>", 1))).hasSize(2);
		assertThat(index.get(new MethodUtils.OverloadKey("<clinit>", 0))).hasSize(1);
		assertThat(index).doesNotContainKey(new MethodUtils.OverloadKey("unknown", 0));
	}

	@Test
	void overloadIndexCandidatesAreDeterministicAndStable() {
		ClspClass cls = new ClspClass(ArgType.object("test.ExternalStable"), 2, 0, ClspClassSource.CORE);
		cls.setMethods(List.of(
				clspMethod("test.ExternalStable", "run", ArgType.INT, ArgType.VOID),
				clspMethod("test.ExternalStable", "run", ArgType.OBJECT, ArgType.VOID),
				clspMethod("test.ExternalStable", "run", ArgType.INT, ArgType.BOOLEAN)));

		MethodInfo query = mthInfo("test.ExternalStable", "run", ArgType.INT, ArgType.VOID);
		List<ClspMethod> first = methodUtils.getOverloadCandidates(cls, query);
		List<ClspMethod> second = methodUtils.getOverloadCandidates(cls, query);

		assertThat(first).isNotNull();
		// cache hit returns the same published bucket instance
		assertThat(second).isSameAs(first);
		// bucket holds all same name + args count methods in encounter order,
		// the query itself is excluded later by the short id check
		assertThat(first).hasSize(3);
		assertThat(first.stream().map(m -> m.getMethodInfo().getShortId()).collect(Collectors.toList()))
				.contains(query.getShortId());
	}

	@Test
	void overloadIndexUsesRawNameNotAlias() {
		ClspClass cls = new ClspClass(ArgType.object("test.ExternalAlias"), 3, 0, ClspClassSource.CORE);
		cls.setMethods(List.of(
				clspMethod("test.ExternalAlias", "foo", ArgType.INT, ArgType.VOID),
				clspMethod("test.ExternalAlias", "foo", ArgType.OBJECT, ArgType.VOID)));

		MethodInfo aliased = mthInfo("test.ExternalAlias", "foo", ArgType.INT, ArgType.VOID);
		aliased.setAlias("renamedFoo");
		List<ClspMethod> candidates = methodUtils.getOverloadCandidates(cls, aliased);
		assertThat(candidates).isNotNull();
		// raw name "foo" is used for the lookup even with a different alias
		assertThat(candidates).hasSize(2);
		for (ClspMethod candidate : candidates) {
			assertThat(candidate.getMethodInfo().getName()).isEqualTo("foo");
		}
	}

	@Test
	void appSourceClassesAreNotIndexed() {
		ClspClass appCls = new ClspClass(ArgType.object("test.AppCls"), 4, 0, ClspClassSource.APP);
		appCls.setMethods(List.of(
				clspMethod("test.AppCls", "foo", ArgType.INT, ArgType.VOID),
				clspMethod("test.AppCls", "foo", ArgType.OBJECT, ArgType.VOID)));

		MethodInfo query = mthInfo("test.AppCls", "foo", ArgType.INT, ArgType.VOID);
		assertThat(methodUtils.getOverloadCandidates(appCls, query)).isNull();
	}

	private MethodInfo mthInfo(String declCls, String name, ArgType argType, ArgType retType) {
		ClassInfo clsInfo = ClassInfo.fromName(root, declCls);
		return MethodInfo.fromDetails(root, clsInfo, name, Collections.singletonList(argType), retType);
	}

	private ClspMethod clspMethod(String declCls, String name, @Nullable ArgType argType, ArgType retType) {
		ClassInfo clsInfo = ClassInfo.fromName(root, declCls);
		List<ArgType> args = argType == null ? Collections.emptyList() : Collections.singletonList(argType);
		MethodInfo mthInfo = MethodInfo.fromDetails(root, clsInfo, name, args, retType);
		return new ClspMethod(mthInfo,
				new ArrayList<>(args),
				retType,
				Collections.emptyList(),
				Collections.emptyList(),
				0);
	}
}
