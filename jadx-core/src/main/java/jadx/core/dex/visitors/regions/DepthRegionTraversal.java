package jadx.core.dex.visitors.regions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.jetbrains.annotations.Nullable;

import jadx.core.dex.nodes.IBlock;
import jadx.core.dex.nodes.IContainer;
import jadx.core.dex.nodes.IRegion;
import jadx.core.dex.nodes.InsnContainer;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.regions.conditions.IfRegion;
import jadx.core.dex.trycatch.ExceptionHandler;
import jadx.core.utils.ListUtils;
import jadx.core.utils.exceptions.JadxRuntimeException;

public class DepthRegionTraversal {

	private static final int ITERATIVE_LIMIT_MULTIPLIER = 5;

	private DepthRegionTraversal() {
	}

	public static void traverse(MethodNode mth, IRegionVisitor visitor) {
		traverseInternal(mth, visitor, mth.getRegion());
	}

	public static void traverse(MethodNode mth, IContainer container, IRegionVisitor visitor) {
		traverseInternal(mth, visitor, container);
	}

	public static <R> @Nullable R traversePartial(MethodNode mth, IRegionPartialVisitor<R> visitor) {
		return traversePartialInternal(mth, visitor, mth.getRegion());
	}

	public static <R> @Nullable R traversePartial(MethodNode mth, IContainer container, IRegionPartialVisitor<R> visitor) {
		return traversePartialInternal(mth, visitor, container);
	}

	public static void traverseIterative(MethodNode mth, IRegionIterativeVisitor visitor) {
		boolean repeat;
		int k = 0;
		int limit = ITERATIVE_LIMIT_MULTIPLIER * mth.getBasicBlocks().size();
		do {
			repeat = traverseIterativeStepInternal(mth, visitor, mth.getRegion());
			if (k++ > limit) {
				throw new JadxRuntimeException("Iterative traversal limit reached: "
						+ "limit: " + limit + ", visitor: " + visitor.getClass().getName()
						+ ", blocks count: " + mth.getBasicBlocks().size());
			}
		} while (repeat);
	}

	public static void traverseIncludingExcHandlers(MethodNode mth, IRegionIterativeVisitor visitor) {
		boolean repeat;
		int k = 0;
		int limit = ITERATIVE_LIMIT_MULTIPLIER * mth.getBasicBlocks().size();
		do {
			repeat = traverseIterativeStepInternal(mth, visitor, mth.getRegion());
			if (!repeat) {
				for (ExceptionHandler h : mth.getExceptionHandlers()) {
					repeat = traverseIterativeStepInternal(mth, visitor, h.getHandlerRegion());
					if (repeat) {
						break;
					}
				}
			}
			if (k++ > limit) {
				throw new JadxRuntimeException("Iterative traversal limit reached: "
						+ "limit: " + limit + ", visitor: " + visitor.getClass().getName()
						+ ", blocks count: " + mth.getBasicBlocks().size());
			}
		} while (repeat);
	}

	private static final IContainer LEAVE_REGION_MARK = new InsnContainer(Collections.emptyList());

	private static void traverseInternal(MethodNode mth, IRegionVisitor visitor, IContainer startContainer) {
		List<IContainer> stack = new ArrayList<>();
		List<IRegion> regionLeaveStack = new ArrayList<>();
		stack.add(startContainer);
		while (true) {
			IContainer current = ListUtils.removeLast(stack);
			if (current == null) {
				return;
			}
			if (current == LEAVE_REGION_MARK) {
				IRegion region = ListUtils.removeLast(regionLeaveStack);
				visitor.leaveRegion(mth, Objects.requireNonNull(region));
			} else if (current instanceof IBlock) {
				visitor.processBlock(mth, (IBlock) current);
			} else if (current instanceof IRegion) {
				IRegion region = (IRegion) current;
				boolean visitRegion = visitor.enterRegion(mth, region);
				stack.add(LEAVE_REGION_MARK);
				regionLeaveStack.add(region);
				if (visitRegion) {
					addSubBlocksToStack(stack, region);
				}
			}
		}
	}

	private static <R> @Nullable R traversePartialInternal(MethodNode mth, IRegionPartialVisitor<R> visitor, IContainer startContainer) {
		List<IContainer> stack = new ArrayList<>();
		stack.add(startContainer);
		while (true) {
			IContainer current = ListUtils.removeLast(stack);
			if (current == null) {
				return null;
			}
			R result = visitor.visit(mth, current);
			if (result != null) {
				return result;
			}
			if (current instanceof IRegion) {
				addSubBlocksToStack(stack, (IRegion) current);
			}
		}
	}

	private static void addSubBlocksToStack(List<IContainer> stack, IRegion region) {
		List<IContainer> subBlocks = region.getSubBlocks();
		// add in reverse order to keep original order during visit
		for (int i = subBlocks.size() - 1; i >= 0; i--) {
			stack.add(subBlocks.get(i));
		}
	}

	private static boolean traverseIterativeStepInternal(MethodNode mth, IRegionIterativeVisitor visitor, IRegion startRegion) {
		List<IRegion> stack = new ArrayList<>();
		stack.add(startRegion);
		while (true) {
			IRegion region = ListUtils.removeLast(stack);
			if (region == null) {
				return false;
			}
			if (visitor.visitRegion(mth, region)) {
				return true;
			}
			List<IContainer> subBlocks = region.getSubBlocks();
			// add in reverse order to keep original order during visit
			for (int i = subBlocks.size() - 1; i >= 0; i--) {
				IContainer subBlock = subBlocks.get(i);
				if (subBlock instanceof IRegion) {
					stack.add((IRegion) subBlock);
				}
			}
		}
	}

	/**
	 * Performs the same sequence of rewrites as {@link #traverseIterative} for a visitor which
	 * replaces a region occurrence with a wrapper region (all other region occurrences keep their
	 * relative DFS order) and which after a rewrite can change its result only for ancestors of
	 * the rewritten region (e.g. removal of a redundant `else` block in {@code IfRegionVisitor}).
	 * <p>
	 * Instead of restarting the scan from the root after each successful rewrite, all candidate
	 * region occurrences are indexed once in DFS visit order and re-checked through an ordered
	 * work set: after a rewrite only affected ancestors are rescheduled, so a chain of K rewrites
	 * over R region occurrences costs O(R + K log R) visits instead of O(K * R).
	 * Falls back to the full rescan traversal if the region tree doesn't satisfy required invariants.
	 */
	static void traverseIterativeWorkSet(MethodNode mth, IRegionIterativeVisitor visitor) {
		IRegion root = mth.getRegion();
		if (root == null) {
			return;
		}
		Map<IRegion, Integer> ranks = new IdentityHashMap<>();
		Map<IRegion, IRegion> parents = new IdentityHashMap<>();
		List<IRegion> candidates = new ArrayList<>();
		if (!indexCandidateRegions(root, ranks, parents, candidates)) {
			traverseIterative(mth, visitor);
			return;
		}
		TreeSet<Integer> pendingRanks = new TreeSet<>();
		for (int rank = candidates.size() - 1; rank >= 0; rank--) {
			pendingRanks.add(rank);
		}
		int limit = ITERATIVE_LIMIT_MULTIPLIER * mth.getBasicBlocks().size();
		boolean repeat;
		int k = 0;
		do {
			repeat = traverseWorkSetStep(mth, visitor, candidates, ranks, parents, pendingRanks);
			if (k++ > limit) {
				throw new JadxRuntimeException("Iterative traversal limit reached: "
						+ "limit: " + limit + ", visitor: " + visitor.getClass().getName()
						+ ", blocks count: " + mth.getBasicBlocks().size());
			}
		} while (repeat);
	}

	private static boolean traverseWorkSetStep(MethodNode mth, IRegionIterativeVisitor visitor,
			List<IRegion> candidates, Map<IRegion, Integer> ranks,
			Map<IRegion, IRegion> parents, TreeSet<Integer> pendingRanks) {
		while (!pendingRanks.isEmpty()) {
			IRegion region = candidates.get(pendingRanks.pollFirst());
			if (visitor.visitRegion(mth, region)) {
				// a rewrite can change results only in ancestors, reschedule them for a re-check
				IRegion ancestor = parents.get(region);
				while (ancestor != null) {
					Integer ancestorRank = ranks.get(ancestor);
					if (ancestorRank != null) {
						pendingRanks.add(ancestorRank);
					}
					ancestor = parents.get(ancestor);
				}
				return true;
			}
		}
		return false;
	}

	private static boolean indexCandidateRegions(IRegion startRegion, Map<IRegion, Integer> ranks,
			Map<IRegion, IRegion> parents, List<IRegion> candidates) {
		List<IRegion> stack = new ArrayList<>();
		List<IRegion> parentStack = new ArrayList<>();
		Set<IRegion> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		stack.add(startRegion);
		parentStack.add(null);
		while (!stack.isEmpty()) {
			IRegion region = ListUtils.removeLast(stack);
			IRegion parent = ListUtils.removeLast(parentStack);
			if (!visited.add(region)) {
				return false; // shared region occurrence or a cycle
			}
			parents.put(region, parent);
			if (region instanceof IfRegion) {
				if (region.getParent() != parent) {
					return false; // inconsistent parent link can change rewrite behavior
				}
				ranks.put(region, candidates.size());
				candidates.add(region);
			}
			List<IContainer> subBlocks = region.getSubBlocks();
			// add in reverse order to keep original order during visit
			for (int i = subBlocks.size() - 1; i >= 0; i--) {
				IContainer subBlock = subBlocks.get(i);
				if (subBlock instanceof IRegion) {
					stack.add((IRegion) subBlock);
					parentStack.add(region);
				}
			}
		}
		return true;
	}
}
