package net.edditech.esdm.index

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Ctrl-click, through the action that implements it.
 *
 * [EsdmReferenceTest] asks the reference to resolve, which it does. That is not
 * the same question as whether the IDE will navigate: "Cannot find declaration
 * to go to" comes from [GotoDeclarationAction], which filters resolve results
 * through its own notion of a usable target.
 */
class EsdmGotoDeclarationTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    private fun gotoTargetAt(relativePath: String, snippet: String, atValue: Boolean = true): String? {
        val root = myFixture.copyDirectoryToProject("library", "library")
        myFixture.openFileInEditor(root.findFileByRelativePath(relativePath)!!)

        val offset = myFixture.editor.document.text.indexOf(snippet)
        assertTrue("snippet not found: $snippet", offset >= 0)
        val caret = if (atValue) offset + snippet.length - 1 else offset
        myFixture.editor.caretModel.moveToOffset(caret)

        val target = GotoDeclarationAction.findTargetElement(project, myFixture.editor, caret)
        val scalar = target as? YAMLScalar ?: return null
        return "${scalar.textValue} (${scalar.containingFile.name})"
    }

    fun testCtrlClickOnADomainReference() {
        assertEquals("library (domain.esdm.yaml)", gotoTargetAt("cataloging/book.esdm.yaml", "domain: library"))
    }

    fun testCtrlClickOnABoundedContextReference() {
        assertEquals(
            "cataloging (book.esdm.yaml)",
            gotoTargetAt("cataloging/book.esdm.yaml", "boundedContext: cataloging"),
        )
    }

    fun testCtrlClickOnACommandReference() {
        assertEquals(
            "stock (copy.esdm.yaml)",
            gotoTargetAt("integration/integration.esdm.yaml", "command: stock"),
        )
    }

    fun testCtrlClickOnAnEventReference() {
        assertEquals(
            "acquired (book.esdm.yaml)",
            gotoTargetAt("integration/integration.esdm.yaml", "event: acquired"),
        )
    }

    fun testCtrlClickOnAFreeStandingEventReference() {
        assertEquals(
            "reserved (reservation-limit.esdm.yaml)",
            gotoTargetAt("reservations/reservation-limit.esdm.yaml", "event: reserved"),
        )
    }

    /**
     * The condition these fixtures cannot reproduce.
     *
     * Opening a model directory as a bare folder gives a project with no module
     * and no content root. A file-based index only covers indexable file sets,
     * so it stays permanently empty and every reference reported "Cannot find
     * declaration to go to" — while every test here passed, because a test
     * fixture always has a module.
     *
     * The scan is what covers that case, so it is exercised on its own rather
     * than through an index that would mask it.
     */
    fun testResolutionWorksWithoutTheIndex() {
        val root = myFixture.copyDirectoryToProject("library", "library")
        val file = root.findFileByRelativePath("cataloging/book.esdm.yaml")!!
        myFixture.openFileInEditor(file)

        val offset = myFixture.editor.document.text.indexOf("domain: library")
        val scalar = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(offset + "domain: library".length - 1),
            YAMLScalar::class.java,
            false,
        )!!

        val key = EsdmReferences.keyFor(scalar)!!
        val scanned = EsdmReferences.resolveByScanning(project, key, scalar)

        assertEquals(listOf("library"), scanned.map { it.textValue })
    }

    /** The scan must agree with the index, not merely find something. */
    fun testScanAndIndexAgree() {
        val root = myFixture.copyDirectoryToProject("library", "library")
        val file = root.findFileByRelativePath("integration/integration.esdm.yaml")!!
        myFixture.openFileInEditor(file)

        for (snippet in listOf("event: acquired", "command: stock", "boundedContext: lending")) {
            val offset = myFixture.editor.document.text.indexOf(snippet) + snippet.length - 1
            val scalar = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                myFixture.file.findElementAt(offset),
                YAMLScalar::class.java,
                false,
            )!!
            val key = EsdmReferences.keyFor(scalar)!!

            assertEquals(
                "scan and index disagree for $snippet",
                EsdmReferences.resolve(project, key, scalar).map { it.textValue },
                EsdmReferences.resolveByScanning(project, key, scalar).map { it.textValue },
            )
        }
    }
}
