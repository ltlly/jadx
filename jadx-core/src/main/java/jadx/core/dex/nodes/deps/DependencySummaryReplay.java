package jadx.core.dex.nodes.deps;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.visitors.DepthTraversal;
import jadx.core.dex.visitors.IDexTreeVisitor;
import jadx.core.dex.visitors.ClassModifier;
import jadx.core.utils.exceptions.JadxRuntimeException;

/**
 * Original ordered residual-stage dispatcher for certified dependency recovery.
 *
 * <p>Reuses the original visitor instances from the production pass list, so the
 * executed code is byte-identical to a legacy recovery round for the residual
 * stages. Effects are applied in Jadx pass-major order followed by
 * DepthTraversal class/inner/method order, exactly as in legacy processing.
 */
public final class DependencySummaryReplay {
	private static final Logger LOG = LoggerFactory.getLogger(DependencySummaryReplay.class);

	private final RootNode root;

	public DependencySummaryReplay(RootNode root) {
		this.root = root;
	}

	/**
	 * Execute residual recovery operations for a certified class. Must be invoked
	 * while holding the class monitor and only after certificate verification.
	 */
	public void execute(ClassNode topCls, DependencyAnalysisSummary summary) {
		List<IDexTreeVisitor> passes = root.getProcessClasses().getPasses();
		boolean strict = DependencySummaryManager.isStrictChecksEnabled();
		for (IDexTreeVisitor pass : passes) {
			DependencyRecoveryCertificate.PassClassification classification =
					DependencyRecoveryCertificate.classify(pass.getName());
			switch (classification) {
				case RESIDUAL:
					runResidualPass(pass, topCls, strict);
					break;
				case RESIDUAL_PARTIAL:
					runPartialResidual(pass, topCls, strict);
					break;
				case LOCAL_IR_ONLY:
					// proven no observable effect for the certified shape
					break;
				case UNSUPPORTED:
				default:
					throw new JadxRuntimeException("Unsupported pass in certified summary replay: " + pass.getName());
			}
		}
	}

	private void runResidualPass(IDexTreeVisitor pass, ClassNode topCls, boolean strict) {
		try {
			DepthTraversal.visit(pass, topCls);
		} catch (StackOverflowError | BootstrapMethodError | Exception e) {
			if (strict) {
				// fatal equivalence assertion: legacy would have had the same failure
				// inside a full recovery, certification must have prevented this
				throw new JadxRuntimeException("Certified summary residual pass failed: " + pass.getName(), e);
			}
			LOG.error("Certified summary residual pass failed: {}", pass.getName(), e);
			topCls.addError(e.getClass().getSimpleName() + " in summary replay pass: " + pass.getName(), e);
		}
	}

	private void runPartialResidual(IDexTreeVisitor pass, ClassNode topCls, boolean strict) {
		try {
			if (pass instanceof ClassModifier) {
				// pure structural flag operation from the round-2 normalized seed:
				// hide empty synthetic classes exactly as ClassModifier would
				replayEmptySyntheticClassFlag(topCls);
			}
		} catch (StackOverflowError | BootstrapMethodError | Exception e) {
			if (strict) {
				throw new JadxRuntimeException("Certified summary partial residual failed: " + pass.getName(), e);
			}
			LOG.error("Certified summary partial residual failed: {}", pass.getName(), e);
		}
	}

	private static void replayEmptySyntheticClassFlag(ClassNode cls) {
		for (ClassNode inner : cls.getInnerClasses()) {
			replayEmptySyntheticClassFlag(inner);
		}
		if (ClassModifier.isEmptySyntheticClass(cls)) {
			cls.add(AFlag.DONT_GENERATE);
		}
	}
}
