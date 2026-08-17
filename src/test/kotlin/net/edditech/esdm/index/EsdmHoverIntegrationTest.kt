package net.edditech.esdm.index

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Hover through the platform's own lookup, the way the IDE does it.
 *
 * [EsdmDocumentationTest] calls the provider directly, which proves it produces
 * the right HTML but says nothing about whether it is the provider the IDE
 * actually consults. It was not: the JSON Schema documentation won, so every
 * hover showed the schema's note about its own construction. Testing through
 * the platform is the only way to catch that.
 */
class EsdmHoverIntegrationTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    /**
     * @param caretAtEnd true to sit on the last character of [snippet] — the
     *   value — and false to sit on its first, which is the key. Both are places
     *   a mouse lands, and they took different code paths.
     */
    private fun hoverAt(relativePath: String, snippet: String, caretAtEnd: Boolean = true): String {
        val root = myFixture.copyDirectoryToProject("library", "library")
        myFixture.openFileInEditor(root.findFileByRelativePath(relativePath)!!)

        val offset = myFixture.editor.document.text.indexOf(snippet)
        assertTrue("snippet not found: $snippet", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(if (caretAtEnd) offset + snippet.length - 1 else offset)

        val targets = IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
        assertTrue("no documentation target at caret", targets.isNotEmpty())

        return computeDocumentationBlocking(targets.first().createPointer())?.html.orEmpty()
    }

    /** The reported symptom, as reported. */
    fun testHoverOnADeclarationShowsTheArtifactNotTheSchemaInternals() {
        val html = hoverAt("cataloging/book.esdm.yaml", "name: book")

        assertFalse(
            "the schema's note about its own construction must not surface: $html",
            html.contains("Inlined rather than"),
        )
        assertTrue("expected the aggregate's own details: $html", html.contains("book"))
    }

    fun testHoverOnAReferenceShowsItsTarget() {
        val html = hoverAt("cataloging/book.esdm.yaml", "domain: library")

        assertFalse("schema internals leaked: $html", html.contains("Inlined rather than"))
        assertTrue("expected the domain's description: $html", html.contains("A city library"))
    }

    /**
     * `publishes` entries are bare names that `$ref` the shared `name`
     * definition, so they were the other place the schema-maintainer note
     * surfaced.
     */
    /**
     * Reported from the sandbox with a screenshot: hovering produced a popup
     * containing the word "boundedContext" and nothing else — the key's own
     * name, no value, no description. The mouse was over the key, and only the
     * value scalar was being treated as a reference.
     */
    fun testHoverOnTheKeyOfAReferenceShowsTheTarget() {
        val html = hoverAt("cataloging/book.esdm.yaml", "boundedContext: cataloging", caretAtEnd = false)

        assertTrue(
            "hovering the key should describe the bounded context it names: $html",
            html.contains("cataloging"),
        )
        assertTrue("expected the target's scope: $html", html.contains("domain: library"))
    }

    fun testHoverOnTheKeyOfANonReferenceStillDescribesTheField() {
        // `kind` is a schema enum, not a reference — the schema's own
        // documentation is the useful answer and must not be suppressed.
        val html = hoverAt("cataloging/book.esdm.yaml", "kind: aggregate", caretAtEnd = false)
        assertTrue("expected some documentation for the field: $html", html.isNotEmpty())
    }

    /**
     * The chain the hover popup actually uses.
     *
     * Everything above goes through the current `DocumentationTarget` API, and
     * all of it passed while the IDE still showed `boundedContext` and nothing
     * else. The YAML plugin contributes schema documentation as a legacy
     * `lang.documentationProvider`, and that is a separate chain — so this
     * composes the legacy providers for YAML exactly as the platform does and
     * asks who answers.
     */
    private fun legacyDoc(relativePath: String, snippet: String, atValue: Boolean): String? {
        val root = myFixture.copyDirectoryToProject("library", "library")
        myFixture.openFileInEditor(root.findFileByRelativePath(relativePath)!!)

        val offset = myFixture.editor.document.text.indexOf(snippet)
        assertTrue("snippet not found: $snippet", offset >= 0)
        val caret = if (atValue) offset + snippet.length - 1 else offset
        myFixture.editor.caretModel.moveToOffset(caret)

        val original = myFixture.file.findElementAt(caret)!!
        val target = myFixture.file.findReferenceAt(caret)?.resolve() ?: original

        val chain = com.intellij.lang.LanguageDocumentation.INSTANCE
            .forLanguage(org.jetbrains.yaml.YAMLLanguage.INSTANCE)
        return chain.generateDoc(target, original)
    }

    fun testLegacyChainAnswersForAReferenceValue() {
        val html = legacyDoc("cataloging/book.esdm.yaml", "boundedContext: cataloging", atValue = true)

        assertNotNull("nothing answered in the legacy chain", html)
        assertTrue("expected our documentation, got: $html", html!!.contains("bounded-context"))
        assertTrue("expected the target's scope, got: $html", html.contains("domain: library"))
    }

    fun testLegacyChainAnswersForAReferenceKey() {
        val html = legacyDoc("cataloging/book.esdm.yaml", "boundedContext: cataloging", atValue = false)

        assertNotNull("nothing answered in the legacy chain", html)
        assertTrue("expected our documentation, got: $html", html!!.contains("bounded-context"))
    }

    /** Non-references must still fall through to the schema's own description. */
    fun testLegacyChainDefersForNonReferences() {
        val html = legacyDoc("cataloging/book.esdm.yaml", "kind: aggregate", atValue = true)

        assertFalse(
            "we should not be answering for a schema enum, got: $html",
            html.orEmpty().contains("<b>aggregate</b>"),
        )
    }

    fun testHoverOnABareNameDoesNotShowSchemaInternals() {
        val html = hoverAt("cataloging/book.esdm.yaml", "- acquired")

        assertFalse("schema internals leaked: $html", html.contains("Inlined rather than"))
    }
}
