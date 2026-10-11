package jadx.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.DecompilationMode;
import jadx.api.ICodeInfo;
import jadx.api.JadxArgs;
import jadx.api.impl.SimpleCodeInfo;
import jadx.core.codegen.CodeGen;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.DecompileModeOverrideAttr;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.LoadStage;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.nodes.deps.DependencyProcessingProfile;
import jadx.core.dex.nodes.deps.DependencyProcessingProfile.Span;
import jadx.core.dex.nodes.deps.DependencySummaryManager;
import jadx.core.dex.visitors.DepthTraversal;
import jadx.core.dex.visitors.IDexTreeVisitor;
import jadx.core.dex.visitors.MethodThrowsVisitor;
import jadx.core.utils.Utils;
import jadx.core.utils.exceptions.JadxRuntimeException;

import static jadx.core.dex.nodes.ProcessState.GENERATED_AND_UNLOADED;
import static jadx.core.dex.nodes.ProcessState.LOADED;
import static jadx.core.dex.nodes.ProcessState.NOT_LOADED;
import static jadx.core.dex.nodes.ProcessState.PROCESS_COMPLETE;
import static jadx.core.dex.nodes.ProcessState.PROCESS_STARTED;

public class ProcessClass {
	private static final Logger LOG = LoggerFactory.getLogger(ProcessClass.class);

	private static final ICodeInfo NOT_GENERATED = new SimpleCodeInfo("");

	private final List<IDexTreeVisitor> passes;

	public ProcessClass(List<IDexTreeVisitor> passesList) {
		this.passes = passesList;
	}

	/**
	 * Request kind for class processing. {@code METADATA_DEPENDENCY} is used only
	 * by the static dependency loop in {@link #generateCode(ClassNode)} and is the
	 * only request kind allowed to use a certified summary recovery;
	 * {@code LEGACY} (self/codegen/force/method-until) always requires full
	 * body-resident processing.
	 */
	private enum RequestKind {
		LEGACY,
		METADATA_DEPENDENCY
	}

	@Nullable
	private ICodeInfo process(ClassNode cls, boolean codegen) {
		return process(cls, codegen, RequestKind.LEGACY);
	}

	@Nullable
	private ICodeInfo process(ClassNode cls, boolean codegen, RequestKind requestKind) {
		if (!codegen && cls.getState() == PROCESS_COMPLETE) {
			// nothing to do
			return null;
		}
		Utils.checkThreadInterrupt();
		DependencyProcessingProfile profile = cls.root().getDependencyProcessingProfile();
		try (Span span = profile == null
				? null
				: profile.begin(cls, "PROCESS/" + requestKind + '/' + codegen, false, true)) {
			synchronized (cls.getClassInfo()) {
				if (span != null) {
					span.acquiredMonitor(cls.getState() == GENERATED_AND_UNLOADED);
				}
				DependencySummaryManager summaryManager = null;
				if (!codegen && requestKind == RequestKind.METADATA_DEPENDENCY
						&& cls.getState() == GENERATED_AND_UNLOADED) {
					// recheck summary/epoch under the monitor (pre-lock check would be a TOCTOU)
					summaryManager = cls.root().getDependencySummaryManager();
					if (summaryManager.tryRecover(cls)) {
						return null;
					}
					summaryManager.recordFallbackReentry();
				}
				long recoveryStart = summaryManager == null ? 0L : System.nanoTime();
				try {
					if (cls.contains(AFlag.CLASS_DEEP_RELOAD)) {
						cls.remove(AFlag.CLASS_DEEP_RELOAD);
						cls.deepUnload();
						cls.add(AFlag.CLASS_UNLOADED);
					}
					if (cls.contains(AFlag.CLASS_UNLOADED)) {
						cls.root().runPreDecompileStageForClass(cls);
						cls.remove(AFlag.CLASS_UNLOADED);
					}
					if (cls.getState() == GENERATED_AND_UNLOADED) {
						// force loading code again
						cls.setState(NOT_LOADED);
					}
					if (codegen) {
						cls.setLoadStage(LoadStage.CODEGEN_STAGE);
						if (cls.contains(AFlag.RELOAD_AT_CODEGEN_STAGE)) {
							cls.remove(AFlag.RELOAD_AT_CODEGEN_STAGE);
							unloadClass(cls, profile);
						}
					} else {
						cls.setLoadStage(LoadStage.PROCESS_STAGE);
					}
					if (cls.getState() == NOT_LOADED) {
						loadClass(cls, profile);
					}
					if (codegen && cls.getState() == PROCESS_COMPLETE && cls.isSummaryRecoveredResidency()) {
						// summary-only residency: regenerate like legacy does from a
						// recovered resident body — reload bodies, don't re-run passes
						loadClass(cls, profile);
						cls.setState(PROCESS_COMPLETE);
					}
					if (cls.getState() == LOADED) {
						cls.setState(PROCESS_STARTED);
						int ordinal = 0;
						for (IDexTreeVisitor visitor : passes) {
							try (Span passSpan = profile == null
									? null
									: profile.beginStage(cls, "PASS/" + ordinal + '/' + visitor.getName())) {
								DepthTraversal.visit(visitor, cls);
							}
							ordinal++;
						}
						cls.setState(PROCESS_COMPLETE);
					}
					if (codegen) {
						Utils.checkThreadInterrupt();
						MethodThrowsVisitor.refreshPendingThrows(cls);
						ICodeInfo code;
						try (Span codeSpan = profile == null ? null : profile.beginStage(cls, "CODEGEN")) {
							code = CodeGen.generate(cls);
						}
						if (!cls.contains(AFlag.DONT_UNLOAD_CLASS)) {
							// capture prepared summary before the ordinary generated unload
							cls.root().getDependencySummaryManager().prepareAfterCodegen(cls);
							unloadClass(cls, profile);
							cls.setState(GENERATED_AND_UNLOADED);
						}
						return code;
					}
					return null;
				} catch (StackOverflowError | Exception e) {
					if (codegen) {
						throw e;
					}
					cls.addError("Class process error: " + e.getClass().getSimpleName(), e);
					return null;
				} finally {
					if (summaryManager != null) {
						summaryManager.recordLegacyRecovery(System.nanoTime() - recoveryStart);
					}
				}
			}
		}
	}

	private static void loadClass(ClassNode cls, @Nullable DependencyProcessingProfile profile) {
		try (Span span = profile == null ? null : profile.beginStage(cls, "LOAD_CLASS")) {
			cls.load();
		}
	}

	private static void unloadClass(ClassNode cls, @Nullable DependencyProcessingProfile profile) {
		try (Span span = profile == null ? null : profile.beginStage(cls, "UNLOAD_CLASS")) {
			cls.unload();
		}
	}

	@NotNull
	public ICodeInfo generateCode(ClassNode cls) {
		ClassNode topParentClass = cls.getTopParentClass();
		if (topParentClass != cls) {
			return generateCode(topParentClass);
		}
		DependencyProcessingProfile profile = cls.root().getDependencyProcessingProfile();
		try (Span span = profile == null ? null : profile.begin(cls, "GENERATE_REQUEST", false, true)) {
			try {
				if (cls.contains(AFlag.DONT_GENERATE)) {
					process(cls, false);
					return NOT_GENERATED;
				}
				for (ClassNode depCls : cls.getDependencies()) {
					process(depCls, false, RequestKind.METADATA_DEPENDENCY);
				}
				if (!cls.getCodegenDeps().isEmpty()) {
					process(cls, false);
					for (ClassNode codegenDep : cls.getCodegenDeps()) {
						process(codegenDep, false);
					}
				}
				ICodeInfo code = process(cls, true);
				if (code == null) {
					throw new JadxRuntimeException("Codegen failed");
				}
				return code;
			} catch (StackOverflowError | Exception e) {
				throw new JadxRuntimeException("Failed to generate code for class: " + cls.getFullName(), e);
			}
		}
	}

	/**
	 * Load and process class without its deps
	 */
	public void forceProcess(ClassNode cls) {
		ClassNode topParentClass = cls.getTopParentClass();
		if (topParentClass != cls) {
			forceProcess(topParentClass);
			return;
		}
		try {
			process(cls, false);
		} catch (StackOverflowError | Exception e) {
			throw new JadxRuntimeException("Failed to process class: " + cls.getFullName(), e);
		}
	}

	/**
	 * Generate code for class without processing its deps
	 */
	public @Nullable ICodeInfo forceGenerateCode(ClassNode cls) {
		try {
			return process(cls, true);
		} catch (StackOverflowError | Exception e) {
			throw new JadxRuntimeException("Failed to generate code for class: " + cls.getFullName(), e);
		}
	}

	private final Map<DecompilationMode, ProcessClass> modesMap = new EnumMap<>(DecompilationMode.class);

	public @Nullable ICodeInfo forceGenerateCodeForMode(ClassNode cls, DecompilationMode mode) {
		synchronized (modesMap) {
			ProcessClass prCls = modesMap.computeIfAbsent(mode, m -> {
				RootNode root = cls.root();
				ProcessClass newPrCls = new ProcessClass(getPassesForMode(root.getArgs(), m));
				newPrCls.initPasses(root);
				return newPrCls;
			});
			try {
				cls.addAttr(new DecompileModeOverrideAttr(mode));
				return prCls.forceGenerateCode(cls);
			} finally {
				cls.remove(AType.DECOMPILE_MODE_OVERRIDE);
			}
		}
	}

	private static List<IDexTreeVisitor> getPassesForMode(JadxArgs baseArgs, DecompilationMode mode) {
		switch (mode) {
			case FALLBACK:
				return Jadx.getFallbackPassesList();

			case SIMPLE:
				// copy properties into new args
				// keep in sync with properties usage in Jadx.getSimpleModePasses method
				JadxArgs args = new JadxArgs();
				args.setDebugInfo(baseArgs.isDebugInfo());
				args.setCommentsLevel(baseArgs.getCommentsLevel());
				return Jadx.getSimpleModePasses(args);

			default:
				throw new JadxRuntimeException("Unexpected decompilation mode: " + mode);
		}
	}

	public void initPasses(RootNode root) {
		for (IDexTreeVisitor pass : passes) {
			try {
				pass.init(root);
			} catch (Exception e) {
				LOG.error("Visitor init failed: {}", pass.getClass().getSimpleName(), e);
			}
		}
	}

	public boolean processMethodUntilVisitor(MethodNode mth, String visitorName, boolean includeVisitor) {
		IDexTreeVisitor foundPass = null;
		IDexTreeVisitor prevPass = null;
		for (IDexTreeVisitor pass : passes) {
			if (pass.getName().equals(visitorName)) {
				if (includeVisitor) {
					foundPass = pass;
				} else {
					foundPass = prevPass;
				}
				break;
			}
			prevPass = pass;
		}
		if (foundPass == null) {
			return false;
		}
		return processMethodToVisitor(mth, foundPass);
	}

	public boolean processMethodToVisitor(MethodNode mth, IDexTreeVisitor lastPassToProcess) {
		DependencyProcessingProfile profile = mth.root().getDependencyProcessingProfile();
		try (Span span = profile == null ? null : profile.begin(mth.getTopParentClass(), "METHOD_RECOVERY", true, true)) {
			synchronized (mth.getTopParentClass().getClassInfo()) {
				if (span != null) {
					span.acquiredMonitor(true);
				}
				try {
					try (Span loadSpan = profile == null ? null : profile.beginStage(mth.getTopParentClass(), "RELOAD_METHOD")) {
						mth.unload();
						mth.load();
					}
					int ordinal = 0;
					for (IDexTreeVisitor pass : passes) {
						try (Span passSpan = profile == null
								? null
								: profile.beginStage(mth.getTopParentClass(), "METHOD_PASS/" + ordinal + '/' + pass.getName())) {
							DepthTraversal.visit(pass, mth);
						}
						ordinal++;
						if (pass == lastPassToProcess) {
							return true;
						}
					}
				} catch (Exception e) {
					throw new JadxRuntimeException("Failed to process method to visitor: " + lastPassToProcess, e);
				}
				return false;
			}
		}
	}

	// TODO: make passes list private and not visible
	public List<IDexTreeVisitor> getPasses() {
		return passes;
	}
}
