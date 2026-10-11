package jadx.core.dex.visitors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.api.plugins.input.data.attributes.types.ExceptionsAttr;
import jadx.core.Consts;
import jadx.core.clsp.ClspClass;
import jadx.core.clsp.ClspMethod;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.MethodThrowsAttr;
import jadx.core.dex.info.ClassInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.InvokeNode;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.InsnWrapArg;
import jadx.core.dex.instructions.args.RegisterArg;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.trycatch.CatchAttr;
import jadx.core.dex.trycatch.ExceptionHandler;
import jadx.core.dex.visitors.regions.RegionMakerVisitor;
import jadx.core.dex.visitors.typeinference.TypeCompare;
import jadx.core.dex.visitors.typeinference.TypeCompareEnum;
import jadx.core.utils.ListUtils;
import jadx.core.utils.exceptions.JadxException;

@JadxVisitor(
		name = "MethodThrowsVisitor",
		desc = "Scan methods to collect thrown exceptions",
		runAfter = {
				RegionMakerVisitor.class // Run after RegionMakerVisitor to ignore throw instructions of synchronized regions
		}
)
public class MethodThrowsVisitor extends AbstractVisitor {

	private enum ExceptionType {
		THROWS_REQUIRED, RUNTIME, UNKNOWN_TYPE, NO_EXCEPTION
	}

	private RootNode root;

	private static final class ThrowsAnalysis {
		private final Map<MethodNode, Set<String>> methods = new LinkedHashMap<>();
		private boolean pending;
	}

	@Override
	public void init(RootNode root) throws JadxException {
		this.root = root;
	}

	@Override
	public void visit(MethodNode mth) throws JadxException {
		MethodThrowsAttr attr = mth.get(AType.METHOD_THROWS);
		if (attr != null && attr.isVisited()) {
			return;
		}
		ThrowsAnalysis analysis = new ThrowsAnalysis();
		analyzeMethod(mth, analysis);
		long previousSize;
		long size = countExceptions(analysis);
		do {
			previousSize = size;
			for (MethodNode method : new ArrayList<>(analysis.methods.keySet())) {
				processInstructions(method, analysis);
			}
			size = countExceptions(analysis);
		} while (size != previousSize);
		for (Map.Entry<MethodNode, Set<String>> entry : analysis.methods.entrySet()) {
			Set<String> exceptions = entry.getValue();
			mergeExceptions(exceptions);
			// Publish only stable sets; a pending analysis is replaced on its next visit.
			MethodThrowsAttr methodAttr = new MethodThrowsAttr(exceptions);
			methodAttr.setVisited(!analysis.pending);
			entry.getKey().addAttr(methodAttr);
		}
	}

	private static long countExceptions(ThrowsAnalysis analysis) {
		long count = 0;
		for (Set<String> exceptions : analysis.methods.values()) {
			count += exceptions.size();
		}
		return count;
	}

	private void analyzeMethod(MethodNode mth, ThrowsAnalysis analysis) {
		MethodThrowsAttr existing = mth.get(AType.METHOD_THROWS);
		if ((existing != null && existing.isVisited()) || analysis.methods.containsKey(mth)) {
			return;
		}
		if (!mth.isNoCode() && (mth.getBasicBlocks() == null || mth.getRegion() == null)) {
			analysis.pending = true;
			return;
		}
		Set<String> throwsSet = new HashSet<>();
		analysis.methods.put(mth, throwsSet);
		List<ArgType> invalid = new ArrayList<>();
		ExceptionsAttr exceptions = mth.get(JadxAttrType.EXCEPTIONS);
		if (exceptions != null && !exceptions.getList().isEmpty()) {
			for (String throwsTypeStr : exceptions.getList()) {
				ArgType excType = ArgType.object(throwsTypeStr);
				if (validateException(excType) == ExceptionType.NO_EXCEPTION) {
					invalid.add(excType);
				} else {
					throwsSet.add(excType.getObject());
				}
			}
		}
		if (!invalid.isEmpty()) {
			mth.addWarnComment("Byte code manipulation detected: skipped illegal throws declarations: " + invalid);
		}
		processInstructions(mth, analysis);
	}

	private void mergeExceptions(Set<String> excSet) {
		if (excSet.contains(Consts.CLASS_EXCEPTION)) {
			excSet.removeIf(e -> !e.equals(Consts.CLASS_EXCEPTION));
			return;
		}
		if (excSet.contains(Consts.CLASS_THROWABLE)) {
			excSet.removeIf(e -> !e.equals(Consts.CLASS_THROWABLE));
			return;
		}
		List<String> toRemove = new ArrayList<>();
		for (String ex1 : excSet) {
			for (String ex2 : excSet) {
				if (ex1.equals(ex2)) {
					continue;
				}
				if (isBaseException(ex1, ex2)) {
					toRemove.add(ex1);
				}
			}
		}
		toRemove.forEach(excSet::remove);
	}

	private void processInstructions(MethodNode mth, ThrowsAnalysis analysis) {
		if (mth.isNoCode() || mth.getBasicBlocks() == null) {
			return;
		}
		try {
			blocks: for (BlockNode block : mth.getBasicBlocks()) {
				// Skip e.g. throw instructions of synchronized regions
				boolean skipExceptions = block.contains(AFlag.REMOVE) || block.contains(AFlag.DONT_GENERATE);
				Set<String> excludedExceptions = new HashSet<>();
				CatchAttr catchAttr = block.get(AType.EXC_CATCH);
				if (catchAttr != null) {
					for (ExceptionHandler handler : catchAttr.getHandlers()) {
						if (handler.isCatchAll()) {
							continue blocks;
						}
						excludedExceptions.add(handler.getArgType().toString());
					}
				}
				for (InsnNode insn : block.getInstructions()) {
					checkInsn(mth, insn, excludedExceptions, skipExceptions, analysis);
				}
			}
		} catch (Exception e) {
			analysis.pending = true;
			mth.addWarnComment("Failed to analyze thrown exceptions", e);
		}
	}

	private void checkInsn(MethodNode mth, InsnNode insn, Set<String> excludedExceptions, boolean skipExceptions,
			ThrowsAnalysis analysis) throws JadxException {
		if (!skipExceptions && insn.getType() == InsnType.THROW && !insn.contains(AFlag.DONT_GENERATE)) {
			InsnArg throwArg = insn.getArg(0);
			if (throwArg instanceof RegisterArg) {
				RegisterArg regArg = (RegisterArg) throwArg;
				ArgType exceptionType = regArg.getType();
				if (exceptionType.equals(ArgType.THROWABLE)) {

					InsnNode assignInsn = regArg.getAssignInsn();
					if (assignInsn != null
							&& assignInsn.getType() == InsnType.MOVE_EXCEPTION
							&& assignInsn.getResult().contains(AFlag.CUSTOM_DECLARE)) {
						// arg variable is from catch statement, ignore Throwable rethrow
						return;
					}
				}
				visitThrows(mth, exceptionType, excludedExceptions, analysis);
			} else {
				if (throwArg instanceof InsnWrapArg) {
					InsnWrapArg insnWrapArg = (InsnWrapArg) throwArg;
					ArgType exceptionType = insnWrapArg.getType();
					visitThrows(mth, exceptionType, excludedExceptions, analysis);
				}
			}
			return;
		}

		if (insn.getType() == InsnType.INVOKE) {
			InvokeNode invokeNode = (InvokeNode) insn;
			MethodInfo callMth = invokeNode.getCallMth();
			String signature = callMth.makeSignature(true);
			ClassInfo classInfo = callMth.getDeclClass();

			ClassNode classNode = root.resolveClass(classInfo);
			if (classNode != null) {
				MethodNode cMth = searchOverriddenMethod(classNode, callMth, signature);
				if (cMth == null) {
					return;
				}
				for (String argTypeStr : getCalleeThrows(mth, cMth, analysis)) {
					visitThrows(mth, ArgType.object(argTypeStr), excludedExceptions, analysis);
				}
			} else {
				ClspClass clsDetails = root.getClsp().getClsDetails(classInfo.getType());
				if (clsDetails != null) {
					ClspMethod cMth = searchOverriddenMethod(clsDetails, signature);
					if (cMth != null && cMth.getThrows() != null && !cMth.getThrows().isEmpty()) {
						for (ArgType argType : cMth.getThrows()) {
							visitThrows(mth, argType, excludedExceptions, analysis);
						}
					}
				}
			}
		}
	}

	private Iterable<String> getCalleeThrows(MethodNode mth, MethodNode callee, ThrowsAnalysis analysis) {
		boolean local = mth.getTopParentClass() == callee.getTopParentClass();
		if (local) {
			analyzeMethod(callee, analysis);
			Set<String> exceptions = analysis.methods.get(callee);
			if (exceptions != null) {
				return exceptions;
			}
		}
		// Completed throws are pinned across unloads; foreign IR itself can still be incomplete.
		MethodThrowsAttr attr = callee.get(AType.METHOD_THROWS);
		if (attr != null) {
			analysis.pending |= !attr.isVisited();
			return attr.getList();
		}
		if (!callee.isNoCode()) {
			analysis.pending = true;
		}
		ExceptionsAttr exceptions = callee.get(JadxAttrType.EXCEPTIONS);
		return exceptions == null
				? Collections.emptyList()
				: ListUtils.filter(exceptions.getList(), type -> validateException(ArgType.object(type)) != ExceptionType.NO_EXCEPTION);
	}

	private void visitThrows(MethodNode mth, ArgType excType, Set<String> excludedExceptions, ThrowsAnalysis analysis) {
		if (excType.isTypeKnown() && isThrowsRequired(mth, excType)) {
			for (String excludedException : excludedExceptions) {
				if (isBaseException(excType.getObject(), excludedException)) {
					return;
				}
			}

			analysis.methods.get(mth).add(excType.getObject());
		}
	}

	private boolean isThrowsRequired(MethodNode mth, ArgType type) {
		ExceptionType result = validateException(type);
		if (result == ExceptionType.UNKNOWN_TYPE) {
			mth.addInfoComment("Thrown type has an unknown type hierarchy: " + type);
			return true; // assume an exception
		}
		return result == ExceptionType.THROWS_REQUIRED;
	}

	private ExceptionType validateException(ArgType clsType) {
		if (clsType == null || clsType.equals(ArgType.OBJECT)) {
			return ExceptionType.NO_EXCEPTION;
		}
		if (!clsType.isTypeKnown() || !root.getClsp().isClsKnown(clsType.getObject())) {
			return ExceptionType.UNKNOWN_TYPE;
		}
		if (isImplements(clsType, ArgType.RUNTIME_EXCEPTION) || isImplements(clsType, ArgType.ERROR)) {
			return ExceptionType.RUNTIME;
		}
		if (isImplements(clsType, ArgType.THROWABLE) || isImplements(clsType, ArgType.EXCEPTION)) {
			return ExceptionType.THROWS_REQUIRED;
		}
		return ExceptionType.NO_EXCEPTION;
	}

	/**
	 * @return is 'possibleParent' an exception class of 'exception'
	 */
	private boolean isBaseException(String exception, String possibleParent) {
		if (exception.equals(possibleParent)) {
			return true;
		}
		return root.getClsp().isImplements(exception, possibleParent);
	}

	private boolean isImplements(ArgType type, ArgType baseType) {
		if (type.equals(baseType)) {
			return true;
		}
		return root.getClsp().isImplements(type.getObject(), baseType.getObject());
	}

	private @Nullable MethodNode searchOverriddenMethod(ClassNode cls, MethodInfo mth, String signature) {
		// search by exact full signature (with return value) to fight obfuscation (see test
		// 'TestOverrideWithSameName')
		String shortId = mth.getShortId();
		for (MethodNode supMth : cls.getMethods()) {
			if (supMth.getMethodInfo().getShortId().equals(shortId)) {
				return supMth;
			}
		}
		// search by signature without return value and check if return value is wider type
		for (MethodNode supMth : cls.getMethods()) {
			if (supMth.getMethodInfo().getShortId().startsWith(signature) && !supMth.getAccessFlags().isStatic()) {
				TypeCompare typeCompare = cls.root().getTypeCompare();
				ArgType supRetType = supMth.getMethodInfo().getReturnType();
				ArgType mthRetType = mth.getReturnType();
				TypeCompareEnum res = typeCompare.compareTypes(supRetType, mthRetType);
				if (res.isWider()) {
					return supMth;
				}
			}
		}
		return null;
	}

	private ClspMethod searchOverriddenMethod(ClspClass clsDetails, String signature) {
		Map<String, ClspMethod> methodsMap = clsDetails.getMethodsMap();
		for (Map.Entry<String, ClspMethod> entry : methodsMap.entrySet()) {
			String mthShortId = entry.getKey();
			// do not check full signature, classpath methods can be trusted
			// i.e. doesn't contain methods with same signature in one class
			if (mthShortId.startsWith(signature)) {
				return entry.getValue();
			}
		}
		return null;
	}
}
