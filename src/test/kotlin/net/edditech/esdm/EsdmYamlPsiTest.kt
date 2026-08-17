package net.edditech.esdm

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Smoke test for the foundation everything else rests on: that an `.esdm.yaml`
 * file is parsed by the bundled YAML plugin and that we can reach its PSI from
 * here. If the `org.jetbrains.plugins.yaml` dependency is misconfigured, this
 * fails at class-load time rather than mysteriously at runtime.
 */
class EsdmYamlPsiTest : BasePlatformTestCase() {

    fun testEsdmFileIsParsedAsYaml() {
        val file = myFixture.configureByText(
            "rst.esdm.yaml",
            """
            apiVersion: schema.esdm.io/core/v1
            kind: domain
            name: rst
            """.trimIndent(),
        )

        assertInstanceOf(file, YAMLFile::class.java)

        val keys = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .map { it.keyText }
        assertEquals(listOf("apiVersion", "kind", "name"), keys)
    }

    /**
     * ESDM files routinely hold many documents separated by `---` — the real
     * model has up to 16 in one file. Document-level, not file-level, is the
     * unit the plugin has to reason about, so this pins that assumption down.
     */
    fun testMultipleDocumentsAreSeparate() {
        val file = myFixture.configureByText(
            "bounded-context.esdm.yaml",
            """
            apiVersion: schema.esdm.io/core/v1
            kind: bounded-context
            name: teilnehmer

            ---
            apiVersion: schema.esdm.io/core/v1
            kind: actor
            name: fachkraft
            """.trimIndent(),
        ) as YAMLFile

        assertEquals(2, file.documents.size)

        val kinds = file.documents.map { doc -> kindOf(doc) }
        assertEquals(listOf("bounded-context", "actor"), kinds)
    }

    private fun kindOf(document: YAMLDocument): String? =
        PsiTreeUtil.findChildrenOfType(document, YAMLKeyValue::class.java)
            .firstOrNull { it.keyText == "kind" }
            ?.valueText
}
