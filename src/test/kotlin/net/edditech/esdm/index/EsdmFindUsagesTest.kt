package net.edditech.esdm.index

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageInfo
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Find Usages through the platform's own machinery.
 *
 * Two layers, deliberately separated. Most tests hand the handler its target
 * and check what it finds. The last two check what the IDE can even reach —
 * because it asks `TargetElementUtil` what is under the caret before it looks
 * for a handler, and a handler that is never consulted passes every test of its
 * own logic. That gap is what made hover and navigation look fixed twice when
 * they were not.
 */
class EsdmFindUsagesTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    private fun usagesOf(relativePath: String, declaration: String): List<String> {
        val root = myFixture.copyDirectoryToProject("library", "library")
        myFixture.openFileInEditor(root.findFileByRelativePath(relativePath)!!)

        val offset = myFixture.editor.document.text.indexOf(declaration)
        assertTrue("declaration not found: $declaration", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset + declaration.length - 1)

        val target = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(myFixture.caretOffset),
            YAMLScalar::class.java,
            false,
        )!!
        return myFixture.findUsages(target).map { it.describe() }
    }

    private fun UsageInfo.describe(): String {
        val scalar = element as? YAMLScalar
        return "${scalar?.textValue} in ${element?.containingFile?.name}"
    }

    fun testUsagesOfADomain() {
        val usages = usagesOf("domain.esdm.yaml", "name: library")

        // Every document's `scope.domain` points at it, across all contexts.
        assertTrue("expected many usages, got ${usages.size}", usages.size > 10)
        assertTrue(usages.all { it.startsWith("library in") })
    }

    fun testUsagesOfAnEvent() {
        val usages = usagesOf("cataloging/book.esdm.yaml", "name: acquired")

        // Projected by the catalog read model, by lending's availability view,
        // and handled by the policy that stocks a copy.
        assertContainsElements(
            usages,
            "acquired in views.esdm.yaml",
            "acquired in integration.esdm.yaml",
        )
    }

    fun testUsagesOfACommand() {
        val usages = usagesOf("lending/copy.esdm.yaml", "name: stock")

        assertContainsElements(usages, "stock in integration.esdm.yaml")
    }

    /**
     * A bounded context is addressed with a domain by a context mapping and
     * without one by a subdomain, so it is indexed under two keys. Asking about
     * only one would silently lose half the usages.
     */
    fun testUsagesOfABoundedContextCoverBothSpellings() {
        val usages = usagesOf("lending/copy.esdm.yaml", "name: lending")

        assertTrue("expected usages of the lending context, got $usages", usages.isNotEmpty())
        assertTrue(usages.all { it.startsWith("lending in") })
    }

    // ------------------------------------------- what the IDE can actually reach
    //
    // The tests above hand the handler its target. The IDE does not: it asks
    // TargetElementUtil what is under the caret first, and only then looks for a
    // handler. These two record which caret positions survive that step.

    /** Caret on a reference: the platform resolves it, so the target is found. */
    fun testFindUsagesReachableFromAReference() {
        val root = myFixture.copyDirectoryToProject("library", "library")
        myFixture.openFileInEditor(root.findFileByRelativePath("integration/integration.esdm.yaml")!!)

        val snippet = "event: acquired"
        val offset = myFixture.editor.document.text.indexOf(snippet) + snippet.length - 1
        myFixture.editor.caretModel.moveToOffset(offset)

        val target = com.intellij.codeInsight.TargetElementUtil.getInstance()
            .findTargetElement(
                myFixture.editor,
                com.intellij.codeInsight.TargetElementUtil.getInstance().allAccepted,
                offset,
            )
        assertNotNull("the platform should resolve a reference to its declaration", target)
        assertTrue(EsdmFindUsagesHandlerFactory().canFindUsages(target!!))
    }

    /**
     * Caret on the declaration — the natural place to ask "who uses this?".
     *
     * This found nothing until the declaration was exposed as a `PomTarget`:
     * `ELEMENT_NAME_ACCEPTED` requires a named element, and a YAML scalar is
     * not one.
     */
    fun testFindUsagesReachableFromTheDeclaration() {
        val root = myFixture.copyDirectoryToProject("library", "library")
        myFixture.openFileInEditor(root.findFileByRelativePath("domain.esdm.yaml")!!)

        val snippet = "name: library"
        val offset = myFixture.editor.document.text.indexOf(snippet) + snippet.length - 1
        myFixture.editor.caretModel.moveToOffset(offset)

        val target = com.intellij.codeInsight.TargetElementUtil.getInstance()
            .findTargetElement(
                myFixture.editor,
                com.intellij.codeInsight.TargetElementUtil.getInstance().allAccepted,
                offset,
            )
        assertNotNull("the declaration must be targetable", target)
        assertTrue(
            "the platform's target must be recognised as an ESDM declaration",
            EsdmFindUsagesHandlerFactory().canFindUsages(target!!),
        )
    }

    /** A declaration nobody refers to has no usages, and must not invent any. */
    fun testUnreferencedDeclarationHasNoUsages() {
        val usages = usagesOf("reservations/reservation-limit.esdm.yaml", "name: list-reservations")

        assertEmpty(usages)
    }
}
