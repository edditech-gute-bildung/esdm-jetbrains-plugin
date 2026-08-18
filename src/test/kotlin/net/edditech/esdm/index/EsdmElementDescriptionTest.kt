package net.edditech.esdm.index

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageViewUtil

/**
 * What the platform calls an ESDM declaration when it talks about it.
 *
 * Asserted through `UsageViewUtil`, which is what the rename dialog and the
 * Find Usages header actually call — not through the provider directly, since
 * the bug being fixed was precisely that nothing consulted us and the platform
 * fell back to prettifying a class name.
 */
class EsdmElementDescriptionTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    private var model: com.intellij.openapi.vfs.VirtualFile? = null

    /** Copied once per test: copyDirectoryToProject fails on a second call. */
    private fun model(): com.intellij.openapi.vfs.VirtualFile =
        model ?: myFixture.copyDirectoryToProject("library", "library").also { model = it }

    private fun targetAt(relativePath: String, snippet: String): com.intellij.psi.PsiElement {
        myFixture.openFileInEditor(model().findFileByRelativePath(relativePath)!!)

        // Matched as a whole line: `name: acquire` is a prefix of
        // `name: acquired`, so a plain indexOf lands on the wrong declaration.
        val match = Regex("^\\s*${Regex.escape(snippet)}$", RegexOption.MULTILINE)
            .find(myFixture.editor.document.text)
            ?: error("no line matching `$snippet` in $relativePath")
        val offset = match.range.last
        myFixture.editor.caretModel.moveToOffset(offset)

        return TargetElementUtil.getInstance()
            .findTargetElement(myFixture.editor, TargetElementUtil.getInstance().allAccepted, offset)!!
    }

    /** The reported wart: the dialog named the implementation class. */
    fun testTypeIsTheArtifactKindNotTheClassName() {
        val target = targetAt("cataloging/book.esdm.yaml", "name: book")

        assertEquals("aggregate", UsageViewUtil.getType(target))
        assertFalse(
            "the implementation class must not surface in user-facing text",
            UsageViewUtil.getType(target).contains("Pom", ignoreCase = true),
        )
    }

    fun testTypeFollowsTheKind() {
        assertEquals("event", UsageViewUtil.getType(targetAt("cataloging/book.esdm.yaml", "name: acquired")))
        assertEquals("command", UsageViewUtil.getType(targetAt("cataloging/book.esdm.yaml", "name: acquire")))
        assertEquals("domain", UsageViewUtil.getType(targetAt("domain.esdm.yaml", "name: library")))
        assertEquals(
            "dynamic-consistency-boundary",
            UsageViewUtil.getType(targetAt("reservations/reservation-limit.esdm.yaml", "name: reservation-limit")),
        )
    }

    fun testNameIsTheDeclaredName() {
        val target = targetAt("lending/copy.esdm.yaml", "name: copy")
        assertEquals("copy", UsageViewUtil.getShortName(target))
    }
}
