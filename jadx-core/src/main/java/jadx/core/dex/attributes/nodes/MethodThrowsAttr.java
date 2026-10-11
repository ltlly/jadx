package jadx.core.dex.attributes.nodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import jadx.api.plugins.input.data.attributes.IJadxAttrType;
import jadx.api.plugins.input.data.attributes.PinnedAttribute;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.info.MethodInfo;

public class MethodThrowsAttr extends PinnedAttribute {
	private final Set<String> list;
	private final @Nullable PendingInfo pendingInfo;

	// Publish the completed exception set to callers processing other classes.
	private volatile boolean visited;

	public MethodThrowsAttr(Set<String> list) {
		this(list, null);
	}

	public MethodThrowsAttr(Set<String> list, @Nullable PendingInfo pendingInfo) {
		this.list = list;
		this.pendingInfo = pendingInfo;
	}

	public boolean isVisited() {
		return visited;
	}

	public void setVisited(boolean visited) {
		this.visited = visited;
	}

	public Set<String> getList() {
		return list;
	}

	public @Nullable PendingInfo getPendingInfo() {
		return pendingInfo;
	}

	public static final class PendingInfo {
		private final Set<String> ownThrows;
		private final List<Call> calls;
		private final boolean scanComplete;

		public PendingInfo(Set<String> ownThrows, List<Call> calls, boolean scanComplete) {
			this.ownThrows = Collections.unmodifiableSet(new HashSet<>(ownThrows));
			this.calls = Collections.unmodifiableList(new ArrayList<>(calls));
			this.scanComplete = scanComplete;
		}

		public Set<String> getOwnThrows() {
			return ownThrows;
		}

		public List<Call> getCalls() {
			return calls;
		}

		public boolean isScanComplete() {
			return scanComplete;
		}
	}

	public static final class Call {
		private final MethodInfo method;
		private final Set<String> excludedExceptions;

		public Call(MethodInfo method, Set<String> excludedExceptions) {
			this.method = method;
			this.excludedExceptions = Collections.unmodifiableSet(new HashSet<>(excludedExceptions));
		}

		public MethodInfo getMethod() {
			return method;
		}

		public Set<String> getExcludedExceptions() {
			return excludedExceptions;
		}
	}

	@Override
	public IJadxAttrType<MethodThrowsAttr> getAttrType() {
		return AType.METHOD_THROWS;
	}

	@Override
	public String toString() {
		return "THROWS:" + list;
	}

}
