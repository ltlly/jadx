package jadx.plugins.kotlin.metadata.tests

import jadx.api.JadxInternalAccess
import jadx.api.plugins.pass.types.JadxDependencySummaryPass
import jadx.core.dex.nodes.ClassNode
import jadx.plugins.kotlin.metadata.KotlinMetadataOptions
import jadx.plugins.kotlin.metadata.pass.KotlinMetadataDecompilePass
import jadx.tests.api.SmaliTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Dependency summary no-op capability: the bundled decompile pass must certify
 * plain Java subtrees as no-op and fail closed for any class carrying the
 * Kotlin metadata annotation (including malformed metadata).
 */
class TestKotlinMetadataSummaryCapability : SmaliTest() {

	private fun decompilePass(): JadxDependencySummaryPass = KotlinMetadataDecompilePass(KotlinMetadataOptions())

	private fun resolveClassNode(smaliPath: String, fullName: String): ClassNode {
		jadxDecompiler = loadFiles(listOf(File("src/test/smali", "$smaliPath.smali")))
		val cls = JadxInternalAccess.getRoot(jadxDecompiler!!).resolveClass(fullName)
		check(cls != null) { "class not found: $fullName" }
		return cls
	}

	@Test
	fun plainJavaSubtreeIsNoOp() {
		val plainCls = resolveClassNode("deobf/plain/PlainJavaCls", "deobf.plain.PlainJavaCls")
		assertTrue(decompilePass().isSummaryNoOpFor(plainCls.topParentClass))
	}

	@Test
	fun metadataAnnotatedSubtreeFailsClosed() {
		val kotlinCls = resolveClassNode("deobf/TestKotlinMetadata/a", "deobf.a")
		// metadata annotation present -> whole subtree must fall back
		assertFalse(decompilePass().isSummaryNoOpFor(kotlinCls.topParentClass))
	}
}
