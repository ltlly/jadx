package jadx.core.dex.nodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jadx.api.JavaField;
import jadx.api.plugins.input.data.IFieldData;
import jadx.core.dex.attributes.nodes.NotificationAttrNode;
import jadx.core.dex.info.AccessInfo;
import jadx.core.dex.info.AccessInfo.AFType;
import jadx.core.dex.info.FieldInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.utils.ListUtils;

public class FieldNode extends NotificationAttrNode implements ICodeNode, IFieldInfoRef {

	private final ClassNode parentClass;
	private final FieldInfo fieldInfo;
	private AccessInfo accFlags;

	private ArgType type;

	private volatile List<MethodNode> useIn = Collections.emptyList();

	private JavaField javaNode;

	public static FieldNode build(ClassNode cls, IFieldData fieldData) {
		FieldInfo fieldInfo = FieldInfo.fromRef(cls.root(), fieldData);
		FieldNode fieldNode = new FieldNode(cls, fieldInfo, fieldData.getAccessFlags());
		fieldNode.addAttrs(fieldData.getAttributes());
		return fieldNode;
	}

	public FieldNode(ClassNode cls, FieldInfo fieldInfo, int accessFlags) {
		this.parentClass = cls;
		this.fieldInfo = fieldInfo;
		this.type = fieldInfo.getType();
		this.accFlags = new AccessInfo(accessFlags, AFType.FIELD);
	}

	public void unload() {
		unloadAttributes();
	}

	public void updateType(ArgType type) {
		this.type = type;
	}

	@Override
	public FieldInfo getFieldInfo() {
		return fieldInfo;
	}

	@Override
	public AccessInfo getAccessFlags() {
		return accFlags;
	}

	@Override
	public void setAccessFlags(AccessInfo accFlags) {
		this.accFlags = accFlags;
	}

	public boolean isStatic() {
		return accFlags.isStatic();
	}

	public boolean isInstance() {
		return !accFlags.isStatic();
	}

	public String getName() {
		return fieldInfo.getName();
	}

	public String getAlias() {
		return fieldInfo.getAlias();
	}

	@Override
	public void rename(String alias) {
		fieldInfo.setAlias(alias);
	}

	public ArgType getType() {
		return type;
	}

	@Override
	public ClassNode getDeclaringClass() {
		return parentClass;
	}

	public ClassNode getParentClass() {
		return parentClass;
	}

	public ClassNode getTopParentClass() {
		return parentClass.getTopParentClass();
	}

	/** Stable read-only snapshot; use the node methods to update usage. */
	public List<MethodNode> getUseIn() {
		return useIn;
	}

	public void setUseIn(List<MethodNode> useIn) {
		List<MethodNode> snapshot = ListUtils.immutableCopy(useIn);
		synchronized (root().getUsageInfoLock()) {
			this.useIn = snapshot;
		}
	}

	public void addUseIn(MethodNode mth) {
		synchronized (root().getUsageInfoLock()) {
			List<MethodNode> updated = new ArrayList<>(useIn);
			updated.add(mth);
			useIn = Collections.unmodifiableList(updated);
		}
	}

	public void replaceUseIn(MethodNode oldMth, MethodNode newMth) {
		synchronized (root().getUsageInfoLock()) {
			useIn = Collections.unmodifiableList(ListUtils.safeReplace(new ArrayList<>(useIn), oldMth, newMth));
		}
	}

	@Override
	public String typeName() {
		return "field";
	}

	@Override
	public String getInputFileName() {
		return parentClass.getInputFileName();
	}

	@Override
	public RootNode root() {
		return parentClass.root();
	}

	public JavaField getJavaNode() {
		return javaNode;
	}

	public void setJavaNode(JavaField javaNode) {
		this.javaNode = javaNode;
	}

	@Override
	public AnnType getAnnType() {
		return AnnType.FIELD;
	}

	@Override
	public int hashCode() {
		return fieldInfo.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null || getClass() != obj.getClass()) {
			return false;
		}
		FieldNode other = (FieldNode) obj;
		return fieldInfo.equals(other.fieldInfo);
	}

	@Override
	public String toString() {
		return fieldInfo.getDeclClass() + "." + fieldInfo.getName() + " :" + type;
	}
}
