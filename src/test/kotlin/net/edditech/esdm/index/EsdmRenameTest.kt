package net.edditech.esdm.index

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Rename through the platform's own refactoring, not through the pieces.
 *
 * `myFixture.renameElementAtCaret` runs the same path Shift-F6 does: it asks
 * TargetElementUtil what is under the caret, finds a processor, collects
 * references and applies them. A rename that the IDE would refuse to start
 * fails here rather than passing on a direct call.
 */
class EsdmRenameTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    private lateinit var root: VirtualFile

    private fun openAt(relativePath: String, snippet: String) {
        root = myFixture.copyDirectoryToProject("library", "library")
        myFixture.openFileInEditor(root.findFileByRelativePath(relativePath)!!)

        val offset = myFixture.editor.document.text.indexOf(snippet)
        assertTrue("snippet not found: $snippet", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset + snippet.length - 1)
    }

    private fun textOf(relativePath: String): String {
        val file = root.findFileByRelativePath(relativePath)!!
        return com.intellij.psi.PsiManager.getInstance(project).findFile(file)!!.text
    }

    // ------------------------------------------------------------- the basics

    fun testRenamingAnEventUpdatesItsConsumers() {
        openAt("cataloging/book.esdm.yaml", "name: acquired")
        myFixture.renameElementAtCaret("catalogued")

        // The declaration.
        assertTrue(textOf("cataloging/book.esdm.yaml").contains("name: catalogued"))
        // The command that publishes it.
        assertTrue(textOf("cataloging/book.esdm.yaml").contains("- catalogued"))
        // The read model that projects it, in another file.
        assertTrue(textOf("cataloging/views.esdm.yaml").contains("event: catalogued"))
        // The policy that handles it, across bounded contexts.
        assertTrue(textOf("integration/integration.esdm.yaml").contains("event: catalogued"))

        assertFalse(
            "no reference to the old name may survive",
            textOf("cataloging/views.esdm.yaml").contains("event: acquired"),
        )
    }

    /**
     * Prose is not a reference.
     *
     * The `librarian` actor's responsibilities include "Record newly acquired
     * books in the catalog". That sentence contains the event's name and must
     * survive renaming it — a text-based rename would have quietly rewritten
     * English into "Record newly catalogued books". Renaming by key rather than
     * by text is what makes the difference, and this pins it down.
     */
    fun testRenameLeavesProseAlone() {
        openAt("cataloging/book.esdm.yaml", "name: acquired")
        myFixture.renameElementAtCaret("catalogued")

        assertTrue(
            "prose mentioning the name must be untouched",
            textOf("cataloging/views.esdm.yaml").contains("Record newly acquired books in the catalog"),
        )
    }

    fun testRenamingADomainUpdatesEveryScope() {
        openAt("domain.esdm.yaml", "name: library")
        myFixture.renameElementAtCaret("biblioteca")

        listOf("cataloging/book.esdm.yaml", "lending/copy.esdm.yaml", "reservations/reservation-limit.esdm.yaml")
            .forEach { path ->
                assertTrue("$path should reference the renamed domain", textOf(path).contains("domain: biblioteca"))
                assertFalse("$path still mentions the old domain", textOf(path).contains("domain: library"))
            }
    }

    /**
     * The interesting one. Renaming an aggregate changes the `scope.aggregate`
     * of its own commands and events — which changes *those* documents' keys —
     * and the `aggregate:` inside every event reference pointing at them. All of
     * it has to move together or the model stops resolving.
     */
    fun testRenamingAnAggregateCascadesThroughEventReferences() {
        openAt("lending/copy.esdm.yaml", "name: copy")
        myFixture.renameElementAtCaret("volume")

        val declarations = textOf("lending/copy.esdm.yaml")
        assertTrue("the aggregate itself", declarations.contains("name: volume"))
        assertTrue("its commands' and events' scope", declarations.contains("aggregate: volume"))
        assertFalse("nothing may still say copy", declarations.contains("aggregate: copy"))

        // Event references in another file carry the owning aggregate too.
        val views = textOf("lending/views.esdm.yaml")
        assertTrue("event references must follow", views.contains("aggregate: volume"))
        assertFalse(views.contains("aggregate: copy"))

        // And the policy in a third file that emits a command to it.
        assertTrue(textOf("integration/integration.esdm.yaml").contains("aggregate: volume"))
    }

    /** A bounded context answers to two keys; both spellings must be rewritten. */
    fun testRenamingABoundedContextUpdatesBothSpellings() {
        openAt("lending/copy.esdm.yaml", "name: lending")
        myFixture.renameElementAtCaret("circulation")

        assertTrue(textOf("lending/copy.esdm.yaml").contains("name: circulation"))
        assertTrue(
            "the context mapping's consumer, which qualifies with a domain",
            textOf("integration/integration.esdm.yaml").contains("boundedContext: circulation"),
        )
        assertTrue(
            "event references, which do not qualify with a domain",
            textOf("lending/views.esdm.yaml").contains("boundedContext: circulation"),
        )
    }

    // ------------------------------------------------------------------ safety

    /** Resolution is by key, so an unrelated declaration of the same name is untouched. */
    fun testRenameDoesNotTouchAnUnrelatedSameName() {
        // `borrower` is an actor in BOTH lending and reservations — two distinct
        // declarations that happen to share a word.
        openAt("lending/views.esdm.yaml", "name: borrower")
        myFixture.renameElementAtCaret("member")

        assertTrue("the lending actor is renamed", textOf("lending/views.esdm.yaml").contains("name: member"))
        assertTrue(
            "the reservations actor of the same name must be left alone",
            textOf("reservations/reservation-limit.esdm.yaml").contains("name: borrower"),
        )
    }

    fun testInvalidNamesAreRejected() {
        openAt("cataloging/book.esdm.yaml", "name: acquired")
        val target = com.intellij.codeInsight.TargetElementUtil.getInstance()
            .findTargetElement(
                myFixture.editor,
                com.intellij.codeInsight.TargetElementUtil.getInstance().allAccepted,
                myFixture.caretOffset,
            )!!

        val validator = EsdmNamesValidator()
        val context = com.intellij.util.ProcessingContext()

        listOf("Acquired", "acquired_2", "2acquired", "acquired!", "").forEach { bad ->
            assertFalse("`$bad` violates the schema's name pattern", validator.isInputValid(bad, target, context))
        }
        listOf("acquired", "book-acquired", "a1").forEach { good ->
            assertTrue("`$good` is a valid ESDM name", validator.isInputValid(good, target, context))
        }
    }

    /**
     * The check that actually matters: after renaming, does the real linter
     * still accept the model?
     *
     * Every assertion above tests what the plugin believes. This one asks
     * `esdm lint` — a separate implementation of the same reference rules,
     * written by someone else — whether the result is a valid ESDM model. If a
     * rename left one reference behind, it says so.
     *
     * Skipped when the binary is absent, since CI has none.
     */
    fun testRenamedModelStillLintsClean() {
        val executable = java.io.File("esdm").absoluteFile
        if (!executable.canExecute()) {
            println("skipping: no esdm binary at ${executable.path}")
            return
        }

        openAt("lending/copy.esdm.yaml", "name: copy")

        val settings = net.edditech.esdm.settings.EsdmSettings.getInstance(project)
        settings.state.esdmPath = executable.path

        val views = root.findFileByRelativePath("lending/views.esdm.yaml")!!
        assertEmpty(
            "the fixture must be clean before the rename",
            net.edditech.esdm.lint.EsdmLintService.getInstance(project).findingsFor(views)!!.findings,
        )

        myFixture.renameElementAtCaret("volume")

        // The lint service mirrors the editor's text to disk, so this lints the
        // renamed model exactly as it stands in the IDE — unsaved and all.
        val outcome = net.edditech.esdm.lint.EsdmLintService.getInstance(project).findingsFor(views)!!.outcome
        val findings = (outcome as net.edditech.esdm.lint.EsdmCli.Outcome.Findings).findings

        assertEmpty("renaming an aggregate must leave a valid model, got $findings", findings)
    }

    /** Every declaration renamed must still be reachable afterwards. */
    fun testModelStillResolvesAfterRename() {
        openAt("cataloging/book.esdm.yaml", "name: acquired")
        myFixture.renameElementAtCaret("catalogued")

        val views = root.findFileByRelativePath("cataloging/views.esdm.yaml")!!
        myFixture.openFileInEditor(views)
        val offset = myFixture.editor.document.text.indexOf("event: catalogued")
        val scalar = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(offset + "event: catalogued".length - 1),
            YAMLScalar::class.java,
            false,
        )!!

        val key = EsdmReferences.keyFor(scalar)!!
        assertEquals(
            listOf("catalogued"),
            EsdmReferences.resolve(project, key, scalar).map { it.textValue },
        )
    }
}
