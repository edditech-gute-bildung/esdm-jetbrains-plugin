package net.edditech.esdm.index

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Navigation from a reference to its declaration.
 *
 * The acceptance criteria are `domain`, `bounded-context`, `command` and
 * `event`. The last of those is the one with teeth: an event reference comes in
 * two shapes that look almost identical and must never resolve to one another.
 */
class EsdmReferenceTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    private fun openModel() = myFixture.copyDirectoryToProject("library", "library")

    /** Resolves the reference on the scalar at `<caret>` and describes the target. */
    private fun resolveAtCaret(): List<String> {
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
            ?: return emptyList()
        val results = (reference as PsiPolyVariantReference).multiResolve(false)

        return results.mapNotNull { result ->
            val scalar = result.element as? YAMLScalar ?: return@mapNotNull null
            val document = PsiTreeUtil.getParentOfType(scalar, YAMLDocument::class.java)
            val mapping = document?.topLevelValue as? YAMLMapping
            val kind = (mapping?.getKeyValueByKey("kind")?.value as? YAMLScalar)?.textValue
            "$kind ${scalar.textValue} (${scalar.containingFile.name})"
        }
    }

    /**
     * Edits the open document and commits it. Without the commit the PSI still
     * describes the previous text, so offsets resolve against stale elements —
     * which is exactly how two of these tests first "failed".
     */
    private fun edit(transform: (String) -> String) {
        val document = myFixture.editor.document
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.setText(transform(document.text))
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun caretOnText(snippet: String) {
        val offset = myFixture.editor.document.text.indexOf(snippet)
        assertTrue("snippet not found: $snippet", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset + snippet.length - 1)
    }

    private fun caretOn(relativePath: String, snippet: String) {
        val root = openModel()
        val file = root.findFileByRelativePath(relativePath)!!
        myFixture.openFileInEditor(file)

        val offset = myFixture.editor.document.text.indexOf(snippet)
        assertTrue("snippet not found in $relativePath: $snippet", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset + snippet.length - 1)
    }

    // ------------------------------------------------------ acceptance criteria

    fun testDomainReferenceResolves() {
        caretOn("cataloging/book.esdm.yaml", "domain: library")
        assertEquals(listOf("domain library (domain.esdm.yaml)"), resolveAtCaret())
    }

    fun testBoundedContextReferenceResolves() {
        caretOn("cataloging/book.esdm.yaml", "boundedContext: cataloging")
        assertEquals(listOf("bounded-context cataloging (book.esdm.yaml)"), resolveAtCaret())
    }

    fun testCommandReferenceResolves() {
        // policy.emits -> {boundedContext, aggregate, command}
        caretOn("integration/integration.esdm.yaml", "command: stock")
        assertEquals(listOf("command stock (copy.esdm.yaml)"), resolveAtCaret())
    }

    fun testEventReferenceResolves() {
        // policy.handles -> {boundedContext, aggregate, event}
        caretOn("integration/integration.esdm.yaml", "event: acquired")
        assertEquals(listOf("event acquired (book.esdm.yaml)"), resolveAtCaret())
    }

    // --------------------------------------- the two eventReference variants

    /** A free-standing event is addressed as a pair, and resolves to the DCB context's event. */
    fun testFreeStandingEventReferenceResolves() {
        caretOn("reservations/reservation-limit.esdm.yaml", "event: reserved")
        assertEquals(
            listOf("event reserved (reservation-limit.esdm.yaml)"),
            resolveAtCaret(),
        )
    }

    /**
     * The distinction that matters. `borrowed` is owned by the `copy` aggregate,
     * so a `{boundedContext, event}` pair naming it is a different reference and
     * must not resolve — the schema is explicit that an event's shape is fixed
     * by its own scope and the two variants are not interchangeable.
     */
    fun testPairReferenceDoesNotResolveToAnAggregateOwnedEvent() {
        val root = openModel()
        myFixture.openFileInEditor(root.findFileByRelativePath("lending/views.esdm.yaml")!!)

        edit {
            it.replaceFirst(
                "  - boundedContext: lending\n    aggregate: copy\n    event: borrowed",
                "  - boundedContext: lending\n    event: borrowed",
            )
        }
        caretOnText("    event: borrowed")

        assertEmpty(
            "a pair reference must not find an aggregate-owned event",
            resolveAtCaret(),
        )
    }

    /** And the mirror image: a triple must not find a free-standing event. */
    fun testTripleReferenceDoesNotResolveToAFreeStandingEvent() {
        val root = openModel()
        myFixture.openFileInEditor(root.findFileByRelativePath("reservations/reservation-limit.esdm.yaml")!!)

        edit {
            it.replaceFirst(
                "  - boundedContext: reservations\n    event: reserved",
                "  - boundedContext: reservations\n    aggregate: reservation-limit\n    event: reserved",
            )
        }
        caretOnText("    event: reserved")

        assertEmpty(
            "a triple reference must not find a free-standing event",
            resolveAtCaret(),
        )
    }

    // ------------------------------------------------------------------ hygiene

    fun testUnknownTargetResolvesToNothing() {
        val root = openModel()
        myFixture.openFileInEditor(root.findFileByRelativePath("cataloging/book.esdm.yaml")!!)

        edit { it.replaceFirst("domain: library", "domain: nowhere") }
        caretOnText("domain: nowhere")
        assertEmpty(resolveAtCaret())
    }

    fun testPlainValuesAreNotReferences() {
        caretOn("cataloging/book.esdm.yaml", "kind: aggregate")
        assertEmpty("`kind` is a schema enum, not a reference", resolveAtCaret())
    }

    private fun keyOf(scalar: YAMLScalar) = (scalar.parent as? YAMLKeyValue)?.keyText
}
