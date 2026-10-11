package jadx.plugins.kotlin.metadata.tests

import jadx.api.JadxInternalAccess
import jadx.core.dex.attributes.AType
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

class TestKotlinGetterOverrideReload : SmaliTest() {

	@Test
	fun reloadingBaseKeepsOverrideNames() {
		checkReload(false)
	}

	@Test
	fun unloadingChildBeforeBaseReloadKeepsOverrideNames() {
		checkReload(true)
	}

	private fun checkReload(unloadChild: Boolean) {
		args.pluginOptions = mapOf(
			CLASS_ALIAS_OPT to "no",
			METHOD_ARGS_OPT to "no",
			FIELDS_OPT to "no",
			COMPANION_OPT to "no",
			DATA_CLASS_OPT to "no",
			TO_STRING_OPT to "no",
			GETTERS_OPT to "yes",
		)
		val names = listOf("Base", "Child", "Caller")
		jadxDecompiler = loadFiles(names.map { File("src/test/smali/getters/TestKotlinGetterOverrideReload/$it.smali") })
		val root = JadxInternalAccess.getRoot(jadxDecompiler!!)
		val classes = names.map { checkNotNull(root.resolveClass("getters.reload.$it")) }
		val (base, child, caller) = classes
		val signature = "a()Ljava/lang/String;"
		val originalBase = checkNotNull(base.searchMethodByShortId(signature))
		assertThat(originalBase.get(AType.METHOD_OVERRIDE)).isNotNull()
		assertThat(checkNotNull(base.searchMethodByShortId("c()Ljava/lang/Object;")).get(AType.METHOD_OVERRIDE)).isNotNull()
		assertThat(checkNotNull(child.searchMethodByShortId(signature)).get(AType.METHOD_OVERRIDE)).isNotNull()

		base.decompile()
		val childCode = child.decompile().codeStr
		val callerCode = caller.decompile().codeStr
		val originalAlias = originalBase.alias
		val covariantAlias = checkNotNull(base.searchMethodByShortId("c()Ljava/lang/Object;")).alias
		assertThat(checkNotNull(base.searchMethodByShortId("b()I")).alias).isEqualTo("getCount")
		assertThat(checkNotNull(child.searchMethodByShortId(signature)).alias).isEqualTo(originalAlias)

		if (unloadChild) {
			val oldChild = checkNotNull(child.searchMethodByShortId(signature))
			JadxInternalAccess.convertClassNode(jadxDecompiler!!, child).unload()
			val unloadedChild = checkNotNull(child.searchMethodByShortId(signature))
			assertThat(unloadedChild).isNotSameAs(oldChild)
			assertThat(unloadedChild.get(AType.METHOD_OVERRIDE)).isNull()
		}

		// Public reload rebuilds the base's MethodNodes while caller code stays cached.
		base.reloadCode()
		val reloadedBase = checkNotNull(base.searchMethodByShortId(signature))
		val currentChild = checkNotNull(child.searchMethodByShortId(signature))
		assertThat(reloadedBase).isNotSameAs(originalBase)
		assertThat(reloadedBase.alias).isEqualTo(currentChild.alias).isEqualTo(originalAlias)
		assertThat(checkNotNull(base.searchMethodByShortId("c()Ljava/lang/Object;")).alias)
			.isEqualTo(checkNotNull(child.searchMethodByShortId("c()Ljava/lang/String;")).alias)
			.isEqualTo(covariantAlias)
		assertThat(checkNotNull(base.searchMethodByShortId("b()I")).alias).isEqualTo("getCount")
		if (!unloadChild) {
			assertThat(child.code.codeStr).isEqualTo(childCode)
		}
		assertThat(caller.code.codeStr).isEqualTo(callerCode)
		runChecks(classes)
	}
}
