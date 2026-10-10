package jadx.core.dex.nodes.deps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;
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
 * Built-in certification allowlist for dependency summaries (stage-2a).
 *
 * <p>stage-2a scope (recovery reduction): classes whose only v1 blockers were
 * pinned annotations now certify. {@code AnnotationsAttr},
 * {@code MethodParametersAttr}-style pinned annotation payloads and
 * {@code EncodedValue} survive unload, so the recovery replays the original
 * annotation residual passes ({@code ModVisitor} class annotation constant
 * replacement, {@code NonFinalResIdsVisitor} annotation scan,
 * {@code PrepareForCodeGen} class/field annotation field usage) on the same
 * live pinned objects at their original pass ordinals instead of skipping
 * them. Every residual is the original pass executed via the original
 * {@code DepthTraversal}, so behavior is identical to the legacy second round
 * on the same live seed.
 *
 * <p>TODO-verify verdicts for the conditionally-SKIP candidates of the
 * design pass matrix (CFG/SSA/type/region/shrink): all stay KEEP for coded
 * classes. {@code CheckRegions} emits root-visible warnings on second-round
 * coded methods (CheckRegions.java:68,81) and reads regions/blocks
 * (CheckRegions.java:31-33); {@code PrepareForCodeGen} and
 * {@code ExtractFieldInit} require the full post-visitor block IR of coded
 * methods (PrepareForCodeGen.java:77-91, ExtractFieldInit.java:84-85), which
 * makes the whole upstream CFG/SSA/type/region pipeline a required
 * prerequisite. No diagnostics/effect closure was provable from source
 * reading, so per the fail-closed contract coded classes are not certified
 * ({@code METHOD_WITH_CODE}) until stage-2b evidence exists.
 *
 * <p>T8 guard: {@code MethodParametersAttr} is pinned and
 * {@code DebugInfoApplyVisitor.processMethodParametersAttribute} has no
 * isNoCode guard, so a legacy recovery would rename {@code CodeVar}s of
 * noCode methods while the no-reload recovery cannot — any method carrying
 * that attribute fails the whole subtree ({@code METHOD_PARAMETERS_ATTR}).
 *
 * <p>Every named miss reason keeps fallbacks diagnosable; any doubt falls
 * back to the full legacy recovery.
 */
public final class DependencyRecoveryCertificate {

	/**
	 * Per-pass classification matrix. Every visitor present in the production
	 * pass list must be classified here; {@code RESIDUAL} entries are re-executed
	 * at recovery, {@code LOCAL_IR_ONLY} entries are proven effect-free for the
	 * certified class shape, {@code UNSUPPORTED} entries force a global disable.
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
		// stage-2a annotation residuals: original passes on live pinned annotation
		// payloads, at their original ordinals (Mod class visit replaces annotation
		// constants; NonFinalResIds scans class/method annotations; Prepare collects
		// class/field annotation field usage — method visit no-ops on isNoCode)
		PASS_MATRIX.put("ModVisitor", PassClassification.RESIDUAL);
		PASS_MATRIX.put("NonFinalResIdsVisitor", PassClassification.RESIDUAL);
		PASS_MATRIX.put("PrepareForCodeGen", PassClassification.RESIDUAL);

		// Proven no-op for the certified no-code subtree shape (raw metadata reads
		// only, all guarded by absent code/regions/ctor insns); annotation
		// presence does not change any of these branches
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
		PASS_MATRIX.put("ExtractFieldInit", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("LoopRegionVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("SwitchBreakVisitor", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("MarkMethodsForInline", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("ProcessVariables", PassClassification.LOCAL_IR_ONLY);
		PASS_MATRIX.put("ApplyVariableNames", PassClassification.LOCAL_IR_ONLY);
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
	 * Verify the whole subtree against the allowlist. Must not mutate
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

	/**
	 * Stats-only helper: collect every distinct miss reason of the whole
	 * subtree instead of the first one only (multi-reason miss bitmask).
	 * Read-only, so it may also run on already-verified subtrees.
	 */
	public static List<String> collectAllMissReasons(ClassNode topCls) {
		Set<String> reasons = new TreeSet<>();
		for (ClassNode cls : topCls.getTopParentClass().selfAndInnerClasses()) {
			collectClassReasons(cls, reasons);
		}
		return new ArrayList<>(reasons);
	}

	/**
	 * Stats-only helper: does the certified subtree carry any live pinned
	 * annotation payload (class/field/method level)?
	 */
	public static boolean hasPinnedAnnotations(ClassNode topCls) {
		for (ClassNode cls : topCls.getTopParentClass().selfAndInnerClasses()) {
			if (cls.contains(JadxAttrType.ANNOTATION_LIST)) {
				return true;
			}
			for (FieldNode fld : cls.getFields()) {
				if (fld.contains(JadxAttrType.ANNOTATION_LIST)) {
					return true;
				}
			}
			for (MethodNode mth : cls.getMethods()) {
				if (mth.contains(JadxAttrType.ANNOTATION_LIST)) {
					return true;
				}
			}
		}
		return false;
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
		if (cls.contains(AFlag.PACKAGE_INFO)) {
			return "PACKAGE_INFO";
		}
		for (FieldNode fld : cls.getFields()) {
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

	private static void collectClassReasons(ClassNode cls, Set<String> reasons) {
		if (cls.isEnum() || cls.contains(AType.ENUM_CLASS)) {
			reasons.add("ENUM_CLASS");
		}
		if (cls.isAnonymous()) {
			reasons.add("ANONYMOUS_CLASS");
		}
		if (!cls.getInlinedClasses().isEmpty()) {
			reasons.add("INLINED_CLASSES");
		}
		if (cls.contains(AType.JADX_ERROR)) {
			reasons.add("CLASS_ERROR_STATE");
		}
		if (cls.contains(AFlag.PACKAGE_INFO)) {
			reasons.add("PACKAGE_INFO");
		}
		for (FieldNode fld : cls.getFields()) {
			if (fld.contains(AType.JADX_ERROR)) {
				reasons.add("FIELD_ERROR_STATE");
			}
		}
		for (MethodNode mth : cls.getMethods()) {
			String miss = verifyMethod(mth);
			if (miss != null) {
				reasons.add(miss);
			}
		}
	}

	private static @Nullable String verifyMethod(MethodNode mth) {
		if (!mth.isNoCode()) {
			// stage-2a keeps coded classes fail-closed: no conditional-SKIP pass of
			// the design matrix (CFG/SSA/type/region/shrink) could be proven
			// effect/diagnostic-free for coded methods (see class javadoc)
			return "METHOD_WITH_CODE";
		}
		if (mth.isConstructor() || mth.getMethodInfo().isClassInit()) {
			return "CTOR_OR_CLINIT";
		}
		if (mth.getMethodInfo().getArgumentsTypes().size() > 255) {
			// CheckCode ignore/NPE branch on recovery
			return "TOO_MANY_ARGS";
		}
		if (mth.contains(JadxAttrType.METHOD_PARAMETERS)) {
			// pinned attr; legacy recovery would rename noCode method CodeVars via
			// DebugInfoApplyVisitor.processMethodParametersAttribute (no isNoCode
			// guard), which the no-reload recovery cannot reproduce -> fail closed
			return "METHOD_PARAMETERS_ATTR";
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
