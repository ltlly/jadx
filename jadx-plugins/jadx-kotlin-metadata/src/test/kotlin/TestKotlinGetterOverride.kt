package jadx.plugins.kotlin.metadata.tests

import jadx.api.JadxInternalAccess
import jadx.plugins.kotlin.metadata.KotlinMetadataOptions.Companion.CLASS_ALIAS_OPT
import jadx.plugins.kotlin.metadata.KotlinMetadataOptions.Companion.COMPANION_OPT
import jadx.plugins.kotlin.metadata.KotlinMetadataOptions.Companion.DATA_CLASS_OPT
import jadx.plugins.kotlin.metadata.KotlinMetadataOptions.Companion.FIELDS_OPT
import jadx.plugins.kotlin.metadata.KotlinMetadataOptions.Companion.GETTERS_OPT
import jadx.plugins.kotlin.metadata.KotlinMetadataOptions.Companion.METHOD_ARGS_OPT
import jadx.plugins.kotlin.metadata.KotlinMetadataOptions.Companion.TO_STRING_OPT
import jadx.tests.api.SmaliTest
import jadx.tests.api.utils.assertj.JadxAssertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class TestKotlinGetterOverride : SmaliTest() {

	@Test
	fun firstImplementationBeforeSecond() {
		checkOrder("First", "Second")
	}

	@Test
	fun secondImplementationBeforeFirst() {
		checkOrder("Second", "First")
	}

	private fun checkOrder(first: String, second: String) {
		args.pluginOptions = mapOf(
			CLASS_ALIAS_OPT to "no",
			METHOD_ARGS_OPT to "no",
			FIELDS_OPT to "no",
			COMPANION_OPT to "no",
			DATA_CLASS_OPT to "no",
			TO_STRING_OPT to "no",
			GETTERS_OPT to "yes",
		)
		val names = listOf("Contract", "First", "Second", "Caller")
		jadxDecompiler = loadFiles(names.map { File("src/test/smali/getters/TestKotlinGetterOverride/$it.smali") })
		val root = JadxInternalAccess.getRoot(jadxDecompiler!!)
		val classes = names.map { checkNotNull(root.resolveClass("getters.$it")) }
		val contract = classes[0]
		val signature = "getContext()Ljava/lang/String;"
		val interfaceCode = contract.decompile().codeStr
		assertThat(interfaceCode).contains("String getContext();")

		for (name in listOf(first, second)) {
			val impl = checkNotNull(root.resolveClass("getters.$name"))
			impl.decompile()
			for (cls in classes.take(3)) {
				assertThat(checkNotNull(cls.searchMethodByShortId(signature)).alias)
					.describedAs("%s after processing %s", cls, name)
					.isEqualTo("getContext")
			}
			assertThat(impl).code().containsOne("String getContext() {")
		}
		assertThat(classes[3]).code().containsOne("return continuation.getContext();")
		assertThat(contract.code.codeStr).isEqualTo(interfaceCode)
		runChecks(classes)
	}
}
