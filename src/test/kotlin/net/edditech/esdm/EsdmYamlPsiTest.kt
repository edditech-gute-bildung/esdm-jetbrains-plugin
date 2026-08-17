package net.edditech.esdm

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.TestDataPath
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
@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class EsdmYamlPsiTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

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

    /**
     * Loads a real file from the synthetic `library` fixture, which is the
     * corpus the reference-resolution and rename tests will run against. Guards
     * the testData wiring and the assumption that a bounded-context file holds
     * one document per artifact.
     */
    fun testFixtureFileFromTestData() {
        val file = myFixture.configureByFile("library/lending/copy.esdm.yaml") as YAMLFile

        // bounded-context, aggregate, 3 commands, 3 events.
        assertEquals(8, file.documents.size)
        assertEquals(
            listOf("bounded-context", "aggregate", "command", "event", "command", "event", "command", "event"),
            file.documents.map { kindOf(it) },
        )
    }

    /**
     * The given-when-then extension is a different `apiVersion` and therefore a
     * different schema. Dispatch has to key on that field rather than on the
     * `.feature.esdm.yaml` filename, which is only a convention.
     */
    fun testFeatureFileDeclaresExtensionApiVersion() {
        val file = myFixture.configureByFile("library/lending/copy.feature.esdm.yaml") as YAMLFile

        val document = file.documents.single()
        assertEquals("schema.esdm.io/given-when-then/v1", valueOf(document, "apiVersion"))
        assertEquals("feature", kindOf(document))
    }

    private fun kindOf(document: YAMLDocument): String? = valueOf(document, "kind")

    private fun valueOf(document: YAMLDocument, key: String): String? =
        (document.topLevelValue as? org.jetbrains.yaml.psi.YAMLMapping)
            ?.getKeyValueByKey(key)
            ?.valueText
}
