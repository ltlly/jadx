package jadx.core.dex.nodes.deps;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.MethodThrowsAttr;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

/**
 * Built-in certification allowlist for dependency summaries (v1).
 *
 * <p>Deliberately restrictive: a whole top class subtree certifies only when a
 * pass-by-pass proof shows the legacy recovery round cannot produce any
 * observable effect beyond the small residual set executed by
 * {@link DependencySummaryReplay}. Any doubt falls back to the full legacy
 * recovery. Extend the matrix below to widen coverage; every named miss reason
 * keeps fallbacks diagnosable.
 */
public final class DependencyRecoveryCertificate {

	/**
	 * Per-pass classification matrix. Every visitor present in the production
	 * pass list must be classified here; {@code RESIDUAL} entries are re-executed
	 * at recovery, {@code LOCAL_IR_ONLY} entries are proven effect-free for the
	 * certified class shape, {@code UNSUPPORTED} entries force a global disable.
	 * New coverage is added mechanically by moving a pass to RESIDUAL and adding
	 * its witness to {@link DependencySummaryReplay}.
	 */
	public enum PassClassification {
		LOCAL_IR_ONLY,
		RESIDUAL,
		RESIDUAL_PARTIAL,
		UNSUPPORTED
	}

	private static final Map<String, PassClassification> PASS_MATRIX = new ConcurrentHashMap<>();

	static {
		// Residual passes re-executed at recovery in original pass order
		PASS_MATRIX.put("MethodThrowsVisitor", PassClassification.RESIDUAL);
		PASS_MATRIX.put("FixAccessModifiers", PassClassification.RESIDUAL);
		// Only the empty synthetic class flag operation is replayed from ClassModifier
		PASS_MATRIX.put("ClassModifier", PassClassification.RESIDUAL_PARTIAL);

		// Proven no-op for the certified no-code subtree shape (raw metadata reads
		// only, all guarded by absent code/regions/ctor insns)
		PASS_MATRIX.put("CheckCode", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("DebugInfoAttachVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("AttachTryCatchVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("AttachCommentsVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("AttachMethodDetails", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("ProcessInstructionsVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("BlockSplitter", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("BlockProcessor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("BlockFinisher", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("SSATransform", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("MoveInlineVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("ConstructorVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("InitCodeVariables", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("MarkFinallyVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("ConstInlineVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("TypeInferenceVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("DebugInfoApplyVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("FixTypesVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("FinishTypeInference", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("ProcessKotlinInternals", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("CodeRenameVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("InlineMethods", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("GenericTypesVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("ShadowFieldVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("DeboxingVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("AnonymousClassVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("ModVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("CodeShrinkVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("ReplaceNewArray", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("RegionMakerVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("IfRegionVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("SwitchOverStringVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("ReturnVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("CleanRegions", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("MethodInvokeVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("SimplifyVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("CheckRegions", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("EnumVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("FixSwitchOverEnum", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("NonFinalResIdsVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("ExtractFieldInit", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("LoopRegionVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("SwitchBreakVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("MarkMethodsForInline", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("ProcessVariables", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("ApplyVariableNames", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("PrepareForCodeGen", PassClassification.LOCAL_IR_ONLY);
	}

	public static PassClassification classify(String passName) {
		if (passName.startsWith("Checks-for-")) {
			// debug-only check wrapper (test/debug builds), reads diagnostics only
			return PassClassification.LOCAL_IR_ONLY;
		}
		return PASS_MATRIX.getOrDefault(passName, PassClassification.UNSUPPORTED);
	}

	private DependencyRecoveryCertificate() {
	}

	/**
	 * Verify the whole subtree against the v1 allowlist. Must not mutate
	 * anything: called once at prepare (live codegen state) and again at
	 * recovery before any residual effect.
	 *
	 * @return {@code null} if the subtree certifies, otherwise a stable miss reason
	 */
	public static @Nullable String verifySubtree(ClassNode topCls) {
		for (ClassNode cls : topCls.getTopParentClass().selfAndInnerClasses()) {
			String miss = verifySingleClass(cls);
			if (miss != null) {
				return miss;
			}
		}
		return null;
	}

	private static @Nullable String verifySingleClass(ClassNode cls) {
		if (cls.isEnum() || cls.contains(AType.ENUM_CLASS)) {
			return "ENUM_CLASS";
		}
		if (cls.isAnonymous()) {
			return "ANONYMOUS_CLASS";
		}
		if (!cls.getInlinedClasses().isEmpty()) {
			return "INLINED_CLASSES";
		}
		if (cls.contains(AType.JADX_ERROR)) {
			return "CLASS_ERROR_STATE";
		}
		if (cls.contains(JadxAttrType.ANNOTATION_LIST)) {
			// class annotations can drive usage/flag effects on other classes
			// (PrepareForCodeGen, NonFinalResIdsVisitor)
			return "CLASS_ANNOTATIONS";
		}
		if (cls.contains(AFlag.PACKAGE_INFO)) {
			return "PACKAGE_INFO";
		}
		for (FieldNode fld : cls.getFields()) {
			if (fld.contains(JadxAttrType.ANNOTATION_LIST)) {
				return "FIELD_ANNOTATIONS";
			}
			if (fld.contains(AType.JADX_ERROR)) {
				return "FIELD_ERROR_STATE";
			}
		}
		for (MethodNode mth : cls.getMethods()) {
			String miss = verifyMethod(mth);
			if (miss != null) {
				return miss;
			}
		}
		return null;
	}

	private static @Nullable String verifyMethod(MethodNode mth) {
		if (!mth.isNoCode()) {
			// v1 certifies only methods without code: a full legacy recovery of any
			// code method re-runs IR/SSA/type/inline passes with unbounded effects
			return "METHOD_WITH_CODE";
		}
		if (mth.isConstructor() || mth.getMethodInfo().isClassInit()) {
			return "CTOR_OR_CLINIT";
		}
		if (mth.getMethodInfo().getArgumentsTypes().size() > 255) {
			// CheckCode ignore/NPE branch on recovery
			return "TOO_MANY_ARGS";
		}
		if (mth.contains(JadxAttrType.ANNOTATION_LIST)) {
			return "METHOD_ANNOTATIONS";
		}
		if (mth.contains(AType.JADX_ERROR)) {
			return "METHOD_ERROR_STATE";
		}
		MethodThrowsAttr throwsAttr = mth.get(AType.METHOD_THROWS);
		if (throwsAttr == null || !throwsAttr.isVisited()) {
			// legacy instruction scan may still be pending for this method
			return "THROWS_NOT_VISITED";
		}
		return null;
	}

	/**
	 * Verify that the throw stage witness is still intact for every method:
	 * pinned METHOD_THROWS attr with visited=true must be present, otherwise the
	 * legacy instruction scan would run at recovery and must not be skipped.
	 */
	public static @Nullable String verifyThrowsWitness(ClassNode topCls) {
		for (ClassNode cls : topCls.getTopParentClass().selfAndInnerClasses()) {
			for (MethodNode mth : cls.getMethods()) {
				MethodThrowsAttr throwsAttr = mth.get(AType.METHOD_THROWS);
				if (throwsAttr == null || !throwsAttr.isVisited()) {
					return "THROWS_WITNESS_LOST";
				}
			}
		}
		return null;
	}

	/**
	 * Validate that the actual pass list is fully covered by the built-in matrix
	 * and custom decompile passes implement the summary no-op capability.
	 *
	 * @return {@code null} if valid, otherwise a disable reason
	 */
	public static @Nullable String validatePassList(RootNode root) {
		for (jadx.core.dex.visitors.IDexTreeVisitor visitor : root.getProcessClasses().getPasses()) {
			String className = visitor.getClass().getName();
			if (className.equals("jadx.core.utils.DebugChecksPass")) {
				// debug-check wrapper, present only in test/debug builds
				continue;
			}
			if (className.startsWith("jadx.core.dex.visitors.")) {
				if (classify(visitor.getName()) == PassClassification.UNSUPPORTED) {
					return "UNCLASSIFIED_BUILTIN_PASS:" + visitor.getName();
				}
				continue;
			}
			if (visitor instanceof jadx.api.impl.passes.DecompilePassWrapper) {
				jadx.api.plugins.pass.JadxPass pass = ((jadx.api.impl.passes.DecompilePassWrapper) visitor).getPass();
				if (!(pass instanceof jadx.api.plugins.pass.types.JadxDependencySummaryPass)) {
					return "UNKNOWN_CUSTOM_PASS:" + className;
				}
				continue;
			}
			return "UNKNOWN_PASS:" + className;
		}
		return null;
	}
}
