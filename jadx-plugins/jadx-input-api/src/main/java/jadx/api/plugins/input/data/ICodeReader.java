package jadx.api.plugins.input.data;

import java.util.List;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import jadx.api.plugins.input.insns.InsnData;

public interface ICodeReader {
	ICodeReader copy();

	/**
	 * Declare support for concurrent class-exclusive instruction scanning:
	 * reader instances of different classes can be used by different threads at the same time,
	 * while all methods of one class must stay scanned serially by a single thread.
	 * Requires private scan cursors (independent copies) over immutable input data
	 * and no usage overlapping with class loading or decompilation phases.
	 * Readers without isolation guarantees must keep the default {@code false} (serial fallback).
	 */
	default boolean supportsParallelClassScan() {
		return false;
	}

	void visitInstructions(Consumer<InsnData> insnConsumer);

	int getRegistersCount();

	int getArgsStartReg();

	int getUnitsCount();

	@Nullable
	IDebugInfo getDebugInfo();

	int getCodeOffset();

	List<ITry> getTries();
}
