package jadx.core.dex.nodes;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import jadx.core.utils.Utils;
import jadx.tests.api.SmaliTest;

import static org.assertj.core.api.Assertions.assertThat;

class UsageInfoListLifecycleTest extends SmaliTest {

	@Test
	void indexedUsageLookupSurvivesRenameAndUnload() {
		File dir = new File("src/test/smali/inline/TestConcurrentMethodInline");
		jadxDecompiler = loadFiles(List.of(new File(dir, "Bridge.smali"), new File(dir, "Target.smali"),
				new File(dir, "Left.smali"), new File(dir, "Right.smali")));
		RootNode root = jadxDecompiler.getRoot();
		ClassNode owner = root.resolveClass("inline.concurrent.Target");
		ClassNode bridgeClass = root.resolveClass("inline.concurrent.Bridge");
		ClassNode leftClass = root.resolveClass("inline.concurrent.Left");
		ClassNode rightClass = root.resolveClass("inline.concurrent.Right");
		MethodNode bridge = getMethod(bridgeClass, "bridge");
		MethodNode left = getMethod(leftClass, "call");
		MethodNode right = getMethod(rightClass, "call");
		FieldNode field = getField(owner, "value");
		List<ClassNode> classes = List.of(owner, bridgeClass, leftClass, rightClass);
		classes.forEach(ClassNode::load);
		assertThat(bridge.getInstructions()).isNotEmpty();

		owner.setUseInMth(Collections.nCopies(512, bridge));
		owner.setUseIn(Collections.nCopies(512, bridgeClass));
		field.setUseIn(Collections.nCopies(512, bridge));
		// Promote each list before changing the nodes used as index keys.
		owner.replaceUsage(bridge, left);
		field.replaceUseIn(bridge, left);
		List<MethodNode> previousMethods = owner.getUseInMth();
		List<ClassNode> previousClasses = owner.getUseIn();
		List<MethodNode> previousFieldMethods = field.getUseIn();
		List<MethodNode> previousExpected = new ArrayList<>(Collections.nCopies(512, bridge));
		previousExpected.set(0, left);

		bridgeClass.rename("renamed.UsageBridge");
		leftClass.rename("renamed.UsageLeft");
		bridge.rename("renamedBridge");
		left.rename("renamedCall");
		classes.forEach(ClassNode::unload);
		assertThat(bridge.getInstructions()).isNull();
		assertThat(owner.getUseInMth()).isSameAs(previousMethods);
		assertThat(owner.getUseIn()).isSameAs(previousClasses);
		assertThat(field.getUseIn()).isSameAs(previousFieldMethods);
		classes.forEach(ClassNode::load);
		assertThat(bridge.getInstructions()).isNotEmpty();
		assertThat(bridgeClass.searchMethodByShortId("bridge()V")).isSameAs(bridge);
		assertThat(leftClass.searchMethodByShortId("call()V")).isSameAs(left);

		// Find both an original duplicate key and a key added during promotion.
		owner.replaceUsage(bridge, right);
		field.replaceUseIn(bridge, right);
		owner.replaceUsage(left, right);
		field.replaceUseIn(left, right);
		List<MethodNode> expected = new ArrayList<>(previousExpected);
		expected.set(0, right);
		expected.set(1, right);
		assertThat(owner.getUseInMth()).containsExactlyElementsOf(expected);
		assertThat(field.getUseIn()).containsExactlyElementsOf(expected);
		assertThat(owner.getUseIn()).containsExactlyElementsOf(Utils.collectionMap(expected, MethodNode::getParentClass));
		assertThat(previousMethods).containsExactlyElementsOf(previousExpected);
		assertThat(previousFieldMethods).containsExactlyElementsOf(previousExpected);
		assertThat(previousClasses)
				.containsExactlyElementsOf(Utils.collectionMap(previousExpected, MethodNode::getParentClass));
	}
}
