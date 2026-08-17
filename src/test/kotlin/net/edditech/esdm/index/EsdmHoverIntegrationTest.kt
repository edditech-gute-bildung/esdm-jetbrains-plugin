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

    fun testHoverOnABareNameDoesNotShowSchemaInternals() {
        val html = hoverAt("cataloging/book.esdm.yaml", "- acquired")

        assertFalse("schema internals leaked: $html", html.contains("Inlined rather than"))
    }
}
