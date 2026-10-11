package jadx.core.dex.regions.conditions;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.instructions.IfNode;
import jadx.core.dex.instructions.IfOp;
import jadx.core.dex.instructions.args.InsnArg;

public final class Compare {
	private final IfNode insn;
	private final boolean inverted;

	public Compare(IfNode insn) {
		this(insn, false);
	}

	private Compare(IfNode insn, boolean inverted) {
		insn.add(AFlag.HIDDEN);
		this.insn = insn;
		this.inverted = inverted;
	}

	public IfOp getOp() {
		return inverted ? insn.getOp().invert() : insn.getOp();
	}

	public InsnArg getA() {
		return insn.getArg(0);
	}

	public InsnArg getB() {
		return insn.getArg(1);
	}

	public IfNode getInsn() {
		return insn;
	}

	public Compare invert() {
		// Regions can share the instruction, so inversion must stay local to the comparison.
		return new Compare(insn, !inverted);
	}

	public Compare copy() {
		return new Compare(insn.copyWithoutResult(), inverted);
	}

	public void normalize() {
		insn.normalize();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof Compare)) {
			return false;
		}
		Compare other = (Compare) obj;
		return insn == other.insn && inverted == other.inverted;
	}

	@Override
	public int hashCode() {
		return 31 * insn.hashCode() + Boolean.hashCode(inverted);
	}

	@Override
	public String toString() {
		return getA() + " " + getOp().getSymbol() + ' ' + getB();
	}
}
