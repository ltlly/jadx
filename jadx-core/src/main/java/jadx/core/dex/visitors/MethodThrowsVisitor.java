package jadx.core.dex.visitors;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
import jadx.core.dex.attributes.nodes.MethodThrowsAttr.Call;
import jadx.core.dex.attributes.nodes.MethodThrowsAttr.PendingInfo;
import jadx.core.dex.info.ClassInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.InvokeNode;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.InsnWrapArg;
import jadx.core.dex.instructions.args.NamedArg;
import jadx.core.dex.instructions.args.RegisterArg;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.trycatch.CatchAttr;
import jadx.core.dex.trycatch.ExcHandlerAttr;
import jadx.core.dex.trycatch.ExceptionHandler;
import jadx.core.dex.visitors.regions.RegionMakerVisitor;
import jadx.core.dex.visitors.typeinference.TypeCompare;
import jadx.core.dex.visitors.typeinference.TypeCompareEnum;
import jadx.core.utils.BlockUtils;
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
		private final Map<MethodNode, PendingInfo> facts = new LinkedHashMap<>();
		private boolean pending;
	}

	private static final class ScanFacts {
		private final Set<String> ownThrows;
		private final List<Call> calls = new ArrayList<>();
		private boolean complete = true;

		private ScanFacts(Set<String> ownThrows) {
			this.ownThrows = new HashSet<>(ownThrows);
		}
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
				processInstructions(method, analysis, null);
			}
			size = countExceptions(analysis);
		} while (size != previousSize);
		for (Map.Entry<MethodNode, Set<String>> entry : analysis.methods.entrySet()) {
			Set<String> exceptions = entry.getValue();
			mergeExceptions(exceptions);
			// Publish only stable sets; a pending analysis is replaced on its next visit.
			MethodThrowsAttr methodAttr = new MethodThrowsAttr(exceptions,
					analysis.pending ? analysis.facts.get(entry.getKey()) : null);
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

	/** Refresh captured throws facts under the top class monitor, after dependency processing. */
	public static void refreshPendingThrows(ClassNode cls) {
		MethodThrowsVisitor visitor = new MethodThrowsVisitor();
		visitor.root = cls.root();
		ThrowsAnalysis analysis = new ThrowsAnalysis();
		for (ClassNode inner : cls.selfAndInnerClasses()) {
			for (MethodNode mth : inner.getMethods()) {
				visitor.collectRecordedThrows(mth, analysis);
			}
		}
		long previousSize;
		long size = countExceptions(analysis);
		do {
			previousSize = size;
			for (Map.Entry<MethodNode, PendingInfo> entry : analysis.facts.entrySet()) {
				MethodNode mth = entry.getKey();
				for (Call call : entry.getValue().getCalls()) {
					for (String exception : visitor.getRecordedCalleeThrows(call, analysis)) {
						visitor.visitThrows(mth, ArgType.object(exception), call.getExcludedExceptions(), analysis, null,
								mth.getTopParentClass() == cls);
					}
				}
			}
			size = countExceptions(analysis);
		} while (size != previousSize);
		for (Map.Entry<MethodNode, Set<String>> entry : analysis.methods.entrySet()) {
			MethodNode mth = entry.getKey();
			if (mth.getTopParentClass() == cls) {
				Set<String> exceptions = entry.getValue();
				visitor.mergeExceptions(exceptions);
				MethodThrowsAttr attr = new MethodThrowsAttr(exceptions, analysis.pending ? analysis.facts.get(mth) : null);
				attr.setVisited(!analysis.pending);
				mth.addAttr(attr);
			}
		}
	}

	private void collectRecordedThrows(MethodNode mth, ThrowsAnalysis analysis) {
		ArrayDeque<MethodNode> queue = new ArrayDeque<>();
		queue.add(mth);
		while (!queue.isEmpty()) {
			MethodNode method = queue.removeFirst();
			if (analysis.methods.containsKey(method)) {
				continue;
			}
			MethodThrowsAttr attr = method.get(AType.METHOD_THROWS);
			if (attr == null || attr.isVisited()) {
				analysis.pending |= attr == null && !method.isNoCode();
				continue;
			}
			PendingInfo facts = attr.getPendingInfo();
			if (facts == null || !facts.isScanComplete()) {
				analysis.pending = true;
				continue;
			}
			analysis.methods.put(method, new HashSet<>(facts.getOwnThrows()));
			analysis.facts.put(method, facts);
			for (Call call : facts.getCalls()) {
				MethodNode callee = root.resolveMethod(call.getMethod());
				if (callee == null) {
					analysis.pending = true;
				} else {
					queue.addLast(callee);
				}
			}
		}
	}

	private Iterable<String> getRecordedCalleeThrows(Call call, ThrowsAnalysis analysis) {
		MethodNode callee = root.resolveMethod(call.getMethod());
		if (callee == null) {
			analysis.pending = true;
			return Collections.emptyList();
		}
		Set<String> exceptions = analysis.methods.get(callee);
		if (exceptions != null) {
			return exceptions;
		}
		MethodThrowsAttr attr = callee.get(AType.METHOD_THROWS);
		if (attr != null) {
			analysis.pending |= !attr.isVisited();
			return attr.getList();
		}
		analysis.pending |= !callee.isNoCode();
		return getDeclaredThrows(callee);
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
		ScanFacts facts = new ScanFacts(throwsSet);
		// Later passes can wrap or remove calls and their catch blocks.
		processInstructions(mth, analysis, facts);
		analysis.facts.put(mth, new PendingInfo(facts.ownThrows, facts.calls, facts.complete));
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

	private void processInstructions(MethodNode mth, ThrowsAnalysis analysis, @Nullable ScanFacts facts) {
		if (mth.isNoCode() || mth.getBasicBlocks() == null) {
			return;
		}
		try {
			Set<ExceptionHandler> rethrowingHandlers = collectUncaughtRethrows(mth);
			for (BlockNode block : mth.getBasicBlocks()) {
				// Skip e.g. throw instructions of synchronized regions
				boolean skipExceptions = block.contains(AFlag.REMOVE) || block.contains(AFlag.DONT_GENERATE);
				Set<String> excludedExceptions = new HashSet<>();
				for (InsnNode insn : block.getInstructions()) {
					excludedExceptions.clear();
					// Moved instructions retain their original catch context.
					CatchAttr catchAttr = insn.get(AType.EXC_CATCH);
					if (catchAttr == null) {
						catchAttr = block.get(AType.EXC_CATCH);
					}
					if (collectExcludedExceptions(catchAttr, rethrowingHandlers, excludedExceptions)) {
						checkInsn(mth, insn, excludedExceptions, skipExceptions, analysis, facts);
					}
					if (!rethrowingHandlers.isEmpty()) {
						processWrappedRethrows(mth, insn, catchAttr, skipExceptions, analysis, facts, rethrowingHandlers);
					}
				}
			}
		} catch (Exception e) {
			analysis.pending = true;
			if (facts != null) {
				facts.complete = false;
			} else {
				PendingInfo previous = analysis.facts.get(mth);
				if (previous != null) {
					analysis.facts.put(mth, new PendingInfo(previous.getOwnThrows(), previous.getCalls(), false));
				}
			}
			mth.addWarnComment("Failed to analyze thrown exceptions", e);
		}
	}

	private static boolean collectExcludedExceptions(@Nullable CatchAttr catchAttr, Set<ExceptionHandler> rethrowingHandlers,
			Set<String> excludedExceptions) {
		if (catchAttr != null) {
			for (ExceptionHandler handler : catchAttr.getHandlers()) {
				if (handler.isRemoved() || handler.isFinally() || rethrowingHandlers.contains(handler)) {
					continue;
				}
				if (handler.isCatchAll()) {
					return false;
				}
				for (ClassInfo catchType : handler.getCatchTypes()) {
					excludedExceptions.add(catchType.getRawName());
				}
			}
		}
		return true;
	}

	private void processWrappedRethrows(MethodNode mth, InsnNode insn, @Nullable CatchAttr enclosingCatch, boolean skipExceptions,
			ThrowsAnalysis analysis, @Nullable ScanFacts facts, Set<ExceptionHandler> rethrowingHandlers) throws JadxException {
		// shortcut: scan wrapped calls only for proven rethrow handlers; general wrapped throws need
		// separate coverage.
		for (InsnArg arg : insn.getArguments()) {
			if (!(arg instanceof InsnWrapArg)) {
				continue;
			}
			InsnNode wrapped = ((InsnWrapArg) arg).getWrapInsn();
			CatchAttr catchAttr = wrapped.get(AType.EXC_CATCH);
			if (catchAttr == null) {
				catchAttr = enclosingCatch;
			}
			if (wrapped.getType() == InsnType.INVOKE && catchAttr != null
					&& !Collections.disjoint(catchAttr.getHandlers(), rethrowingHandlers)) {
				Set<String> excludedExceptions = new HashSet<>();
				if (collectExcludedExceptions(catchAttr, rethrowingHandlers, excludedExceptions)) {
					checkInsn(mth, wrapped, excludedExceptions, skipExceptions, analysis, facts);
				}
			}
			processWrappedRethrows(mth, wrapped, catchAttr, skipExceptions, analysis, facts, rethrowingHandlers);
		}
	}

	private static Set<ExceptionHandler> collectUncaughtRethrows(MethodNode mth) {
		if (mth.getExceptionHandlersCount() == 0) {
			return Collections.emptySet();
		}
		Set<ExceptionHandler> handlers = Collections.newSetFromMap(new IdentityHashMap<>());
		for (ExceptionHandler handler : mth.getExceptionHandlers()) {
			if (isRethrowingHandler(handler)) {
				handlers.add(handler);
			}
		}
		return handlers;
	}

	static boolean isRethrowingHandler(ExceptionHandler handler) {
		if (handler.isRemoved() || handler.isFinally()) {
			return false;
		}
		Set<BlockNode> visited = new HashSet<>();
		BlockNode block = handler.getHandlerBlock();
		while (block != null && visited.add(block)) {
			if (block.contains(AFlag.REMOVE) || block.contains(AFlag.DONT_GENERATE)) {
				return false;
			}
			InsnNode insn = BlockUtils.getLastInsn(block);
			if (insn != null && insn.getType() == InsnType.THROW) {
				if (insn.contains(AFlag.DONT_GENERATE) || !(insn.getArg(0) instanceof RegisterArg)) {
					return false;
				}
				CatchAttr catchAttr = insn.get(AType.EXC_CATCH);
				if (catchAttr == null) {
					catchAttr = block.get(AType.EXC_CATCH);
				}
				if (catchAttr != null) {
					for (ExceptionHandler outerHandler : catchAttr.getHandlers()) {
						if (!outerHandler.isRemoved() && !outerHandler.isFinally()) {
							return false;
						}
					}
				}
				Set<ExceptionHandler> caught = Collections.newSetFromMap(new IdentityHashMap<>());
				return isCaughtThrowable((RegisterArg) insn.getArg(0), new IdentityHashMap<>(), caught)
						&& caught.size() == 1 && caught.contains(handler);
			}
			// shortcut: only linear rethrows are transparent; branching handlers need a path proof.
			if (insn != null && (insn.getType() == InsnType.IF || insn.getType() == InsnType.SWITCH)
					|| block.getCleanSuccessors().size() != 1) {
				return false;
			}
			block = block.getCleanSuccessors().get(0);
		}
		return false;
	}

	private void checkInsn(MethodNode mth, InsnNode insn, Set<String> excludedExceptions, boolean skipExceptions,
			ThrowsAnalysis analysis, @Nullable ScanFacts facts) throws JadxException {
		if (!skipExceptions && insn.getType() == InsnType.THROW && !insn.contains(AFlag.DONT_GENERATE)) {
			InsnArg throwArg = insn.getArg(0);
			if (throwArg instanceof RegisterArg) {
				RegisterArg regArg = (RegisterArg) throwArg;
				ArgType exceptionType = regArg.getType();
				if (exceptionType.equals(ArgType.THROWABLE) && isCaughtThrowable(regArg)) {
					// Ignore a rethrow of caught Throwable.
					return;
				}
				visitThrows(mth, exceptionType, excludedExceptions, analysis, facts);
			} else {
				if (throwArg instanceof InsnWrapArg) {
					InsnWrapArg insnWrapArg = (InsnWrapArg) throwArg;
					ArgType exceptionType = insnWrapArg.getType();
					visitThrows(mth, exceptionType, excludedExceptions, analysis, facts);
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
				if (facts != null) {
					facts.calls.add(new Call(cMth.getMethodInfo(), excludedExceptions));
				}
				for (String argTypeStr : getCalleeThrows(mth, cMth, analysis)) {
					visitThrows(mth, ArgType.object(argTypeStr), excludedExceptions, analysis, null);
				}
			} else {
				ClspClass clsDetails = root.getClsp().getClsDetails(classInfo.getType());
				if (clsDetails != null) {
					ClspMethod cMth = searchOverriddenMethod(clsDetails, signature);
					if (cMth != null && cMth.getThrows() != null && !cMth.getThrows().isEmpty()) {
						for (ArgType argType : cMth.getThrows()) {
							visitThrows(mth, argType, excludedExceptions, analysis, facts);
						}
					}
				}
			}
		}
	}

	static boolean isCaughtThrowable(RegisterArg arg) {
		return isCaughtThrowable(arg, new IdentityHashMap<>(), null);
	}

	private static boolean isCaughtThrowable(RegisterArg arg, Map<InsnNode, Boolean> checked,
			@Nullable Set<ExceptionHandler> caughtHandlers) {
		InsnNode assign = arg.getAssignInsn();
		if (assign == null || !arg.getType().equals(ArgType.THROWABLE)) {
			return false;
		}
		Boolean previous = checked.putIfAbsent(assign, Boolean.FALSE);
		if (previous != null) {
			return previous;
		}
		if (assign.getType() == InsnType.PHI) {
			if (assign.getArgsCount() == 0) {
				return false;
			}
			for (InsnArg source : assign.getArguments()) {
				if (!(source instanceof RegisterArg) || !isCaughtThrowable((RegisterArg) source, checked, caughtHandlers)) {
					return false;
				}
			}
			checked.put(assign, Boolean.TRUE);
			return true;
		}
		if (!assign.getResult().contains(AFlag.CUSTOM_DECLARE)) {
			return false;
		}
		if (assign.getType() == InsnType.MOVE_EXCEPTION) {
			ExcHandlerAttr handler = assign.get(AType.EXC_HANDLER);
			if (caughtHandlers != null) {
				if (handler == null) {
					return false;
				}
				caughtHandlers.add(handler.getHandler());
			}
			checked.put(assign, Boolean.TRUE);
			return true;
		}
		if (assign.getType() == InsnType.MOVE && assign.getArgsCount() == 1) {
			InsnArg source = assign.getArg(0);
			ExcHandlerAttr handler = assign.get(AType.EXC_HANDLER);
			boolean caught = source instanceof NamedArg && source.getType().equals(ArgType.THROWABLE)
					&& handler != null && handler.getHandler().getArg() == source;
			if (caught && caughtHandlers != null) {
				caughtHandlers.add(handler.getHandler());
			}
			checked.put(assign, caught);
			return caught;
		}
		return false;
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
		return getDeclaredThrows(callee);
	}

	private Iterable<String> getDeclaredThrows(MethodNode callee) {
		ExceptionsAttr exceptions = callee.get(JadxAttrType.EXCEPTIONS);
		return exceptions == null
				? Collections.emptyList()
				: ListUtils.filter(exceptions.getList(), type -> validateException(ArgType.object(type)) != ExceptionType.NO_EXCEPTION);
	}

	private void visitThrows(MethodNode mth, ArgType excType, Set<String> excludedExceptions, ThrowsAnalysis analysis,
			@Nullable ScanFacts facts) {
		visitThrows(mth, excType, excludedExceptions, analysis, facts, true);
	}

	private void visitThrows(MethodNode mth, ArgType excType, Set<String> excludedExceptions, ThrowsAnalysis analysis,
			@Nullable ScanFacts facts, boolean addComments) {
		if (excType.isTypeKnown() && isThrowsRequired(mth, excType, addComments)) {
			for (String excludedException : excludedExceptions) {
				if (isBaseException(excType.getObject(), excludedException)) {
					return;
				}
			}

			analysis.methods.get(mth).add(excType.getObject());
			if (facts != null) {
				facts.ownThrows.add(excType.getObject());
			}
		}
	}

	private boolean isThrowsRequired(MethodNode mth, ArgType type, boolean addComments) {
		ExceptionType result = validateException(type);
		if (result == ExceptionType.UNKNOWN_TYPE) {
			if (addComments) {
				mth.addInfoComment("Thrown type has an unknown type hierarchy: " + type);
			}
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
