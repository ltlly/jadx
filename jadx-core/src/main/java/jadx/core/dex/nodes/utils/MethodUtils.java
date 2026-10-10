package jadx.core.dex.nodes.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;

import jadx.core.clsp.ClspClass;
import jadx.core.clsp.ClspClassSource;
import jadx.core.clsp.ClspMethod;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.MethodBridgeAttr;
import jadx.core.dex.attributes.nodes.MethodOverrideAttr;
import jadx.core.dex.attributes.nodes.SkipMethodArgsAttr;
import jadx.core.dex.info.ClassInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.BaseInvokeNode;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.IMethodDetails;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.utils.Utils;

public class MethodUtils {
	/**
	 * Kill switch: fall back to the original linear scan over the whole methods map.
	 */
	private static final boolean OVERLOAD_INDEX_ENABLED =
			Boolean.parseBoolean(System.getProperty("jadx.cache.overload-index", "true"));

	// bounds protect against unbounded growth of the per-Root index
	private static final int MAX_INDEXED_CLASSES = 1_024;
	private static final int MAX_INDEXED_METHODS = 65_536;

	// per-Root index for load-frozen external ClspClasses, lives and dies with this RootNode
	private final ConcurrentHashMap<ClspClass, Map<OverloadKey, List<ClspMethod>>> overloadIndexes = new ConcurrentHashMap<>();
	private final AtomicInteger indexedClassCount = new AtomicInteger();
	private final AtomicInteger indexedMethodCount = new AtomicInteger();

	private final RootNode root;

	public MethodUtils(RootNode rootNode) {
		this.root = rootNode;
	}

	@Nullable
	public IMethodDetails getMethodDetails(BaseInvokeNode invokeNode) {
		IMethodDetails methodDetails = invokeNode.get(AType.METHOD_DETAILS);
		if (methodDetails != null) {
			return methodDetails;
		}
		return getMethodDetails(invokeNode.getCallMth());
	}

	@Nullable
	public IMethodDetails getMethodDetails(MethodInfo callMth) {
		MethodNode mthNode = root.resolveMethod(callMth);
		if (mthNode != null) {
			return mthNode;
		}
		return root.getClsp().getMethodDetails(callMth);
	}

	@Nullable
	public MethodNode resolveMethod(BaseInvokeNode invokeNode) {
		IMethodDetails methodDetails = getMethodDetails(invokeNode);
		if (methodDetails instanceof MethodNode) {
			return (MethodNode) methodDetails;
		}
		return null;
	}

	public boolean isSkipArg(BaseInvokeNode invokeNode, InsnArg arg) {
		MethodNode mth = resolveMethod(invokeNode);
		if (mth == null) {
			return false;
		}
		SkipMethodArgsAttr skipArgsAttr = mth.get(AType.SKIP_MTH_ARGS);
		if (skipArgsAttr == null) {
			return false;
		}
		int argIndex = invokeNode.getArgIndex(arg);
		return skipArgsAttr.isSkip(argIndex);
	}

	/**
	 * Search methods with same name and args count in class hierarchy starting from {@code startCls}
	 * Beware {@code startCls} can be different from {@code mthInfo.getDeclClass()}
	 */
	public boolean isMethodArgsOverloaded(ArgType startCls, MethodInfo mthInfo) {
		return processMethodArgsOverloaded(startCls, mthInfo, null);
	}

	public List<IMethodDetails> collectOverloadedMethods(ArgType startCls, MethodInfo mthInfo) {
		List<IMethodDetails> list = new ArrayList<>();
		processMethodArgsOverloaded(startCls, mthInfo, list);
		return list;
	}

	@Nullable
	public ArgType getMethodGenericReturnType(BaseInvokeNode invokeNode) {
		IMethodDetails methodDetails = getMethodDetails(invokeNode);
		if (methodDetails != null) {
			ArgType returnType = methodDetails.getReturnType();
			if (returnType != null && returnType.containsGeneric()) {
				return returnType;
			}
		}
		return null;
	}

	private boolean processMethodArgsOverloaded(ArgType startCls, MethodInfo mthInfo, @Nullable List<IMethodDetails> collectedMths) {
		if (startCls == null || !startCls.isObject()) {
			return false;
		}
		boolean isMthConstructor = mthInfo.isConstructor() || mthInfo.isClassInit();
		ClassNode classNode = root.resolveClass(startCls);
		if (classNode != null) {
			for (MethodNode mth : classNode.getMethods()) {
				if (mthInfo.isOverloadedBy(mth.getMethodInfo())) {
					if (collectedMths == null) {
						return true;
					}
					collectedMths.add(mth);
				}
			}
			if (!isMthConstructor) {
				if (processMethodArgsOverloaded(classNode.getSuperClass(), mthInfo, collectedMths)) {
					if (collectedMths == null) {
						return true;
					}
				}
				for (ArgType parentInterface : classNode.getInterfaces()) {
					if (processMethodArgsOverloaded(parentInterface, mthInfo, collectedMths)) {
						if (collectedMths == null) {
							return true;
						}
					}
				}
			}
		} else {
			ClspClass clsDetails = root.getClsp().getClsDetails(startCls);
			if (clsDetails == null) {
				// class info not available
				return false;
			}
			List<ClspMethod> candidates = getOverloadCandidates(clsDetails, mthInfo);
			if (candidates != null) {
				// bucket holds methods with same name and args count in original encounter order
				for (ClspMethod clspMth : candidates) {
					if (mthInfo.isOverloadedBy(clspMth.getMethodInfo())) {
						if (collectedMths == null) {
							return true;
						}
						collectedMths.add(clspMth);
					}
				}
			} else {
				for (ClspMethod clspMth : clsDetails.getMethodsMap().values()) {
					if (mthInfo.isOverloadedBy(clspMth.getMethodInfo())) {
						if (collectedMths == null) {
							return true;
						}
						collectedMths.add(clspMth);
					}
				}
			}
			if (!isMthConstructor) {
				for (ArgType parent : clsDetails.getParents()) {
					if (processMethodArgsOverloaded(parent, mthInfo, collectedMths)) {
						if (collectedMths == null) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	@Nullable
	List<ClspMethod> getOverloadCandidates(ClspClass clsDetails, MethodInfo mthInfo) {
		if (!OVERLOAD_INDEX_ENABLED || clsDetails.getSource() == ClspClassSource.APP) {
			// app classes are rebuilt on reload, don't cache their methods
			return null;
		}
		Map<OverloadKey, List<ClspMethod>> index = overloadIndexes.get(clsDetails);
		if (index == null) {
			index = tryBuildAndPutIndex(clsDetails);
			if (index == null) {
				return null;
			}
		}
		return index.get(new OverloadKey(mthInfo.getName(), mthInfo.getArgsCount()));
	}

	@Nullable
	private Map<OverloadKey, List<ClspMethod>> tryBuildAndPutIndex(ClspClass clsDetails) {
		int clsMethodCount = clsDetails.getMethodsMap().size();
		if (indexedClassCount.incrementAndGet() > MAX_INDEXED_CLASSES
				|| indexedMethodCount.addAndGet(clsMethodCount) > MAX_INDEXED_METHODS) {
			// over budget: keep the original linear scan for this and further classes
			indexedClassCount.decrementAndGet();
			indexedMethodCount.addAndGet(-clsMethodCount);
			return null;
		}
		Map<OverloadKey, List<ClspMethod>> index = buildOverloadIndex(clsDetails);
		Map<OverloadKey, List<ClspMethod>> prev = overloadIndexes.putIfAbsent(clsDetails, index);
		if (prev != null) {
			// lost a construction race, winner's index is identical: release reserved budget
			indexedClassCount.decrementAndGet();
			indexedMethodCount.addAndGet(-clsMethodCount);
			return prev;
		}
		return index;
	}

	static Map<OverloadKey, List<ClspMethod>> buildOverloadIndex(ClspClass clsDetails) {
		Map<OverloadKey, List<ClspMethod>> index = new HashMap<>();
		for (ClspMethod clspMth : clsDetails.getMethodsMap().values()) {
			MethodInfo mthInfo = clspMth.getMethodInfo();
			index.computeIfAbsent(new OverloadKey(mthInfo.getName(), mthInfo.getArgsCount()), k -> new ArrayList<>())
					.add(clspMth);
		}
		for (Map.Entry<OverloadKey, List<ClspMethod>> entry : index.entrySet()) {
			entry.setValue(Collections.unmodifiableList(entry.getValue()));
		}
		return index;
	}

	static final class OverloadKey {
		private final String name;
		private final int argsCount;
		private final int hash;

		OverloadKey(String name, int argsCount) {
			this.name = name;
			this.argsCount = argsCount;
			this.hash = name.hashCode() * 31 + argsCount;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof OverloadKey)) {
				return false;
			}
			OverloadKey other = (OverloadKey) o;
			return argsCount == other.argsCount && name.equals(other.name);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	@Nullable
	public IMethodDetails getOverrideBaseMth(MethodNode mth) {
		MethodOverrideAttr overrideAttr = mth.get(AType.METHOD_OVERRIDE);
		if (overrideAttr == null) {
			return null;
		}
		return Utils.getOne(overrideAttr.getBaseMethods());
	}

	public ClassInfo getMethodOriginDeclClass(MethodNode mth) {
		IMethodDetails baseMth = getOverrideBaseMth(mth);
		if (baseMth != null) {
			return baseMth.getMethodInfo().getDeclClass();
		}
		MethodBridgeAttr bridgeAttr = mth.get(AType.BRIDGED_BY);
		if (bridgeAttr != null) {
			return getMethodOriginDeclClass(bridgeAttr.getBridgeMth());
		}
		return mth.getMethodInfo().getDeclClass();
	}
}
