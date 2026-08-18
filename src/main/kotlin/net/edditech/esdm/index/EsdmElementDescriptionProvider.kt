package net.edditech.esdm.index

import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewNodeTextLocation
import com.intellij.usageView.UsageViewShortNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Tells the platform what an ESDM declaration *is*, in the user's words.
 *
 * Without this the rename dialog read "Rename Esdm Pom Target 'book' and its
 * usages to:" — the implementation class name, prettified, in a dialog people
 * see on every rename. The platform falls back to that when nothing describes
 * the element, and a `PomTarget` has no natural description of its own.
 *
 * The answer is already in the document: its `kind`. So the dialog says
 * "aggregate 'book'", and Find Usages groups under the real artifact type
 * rather than a class name.
 */
class EsdmElementDescriptionProvider : ElementDescriptionProvider {

    override fun getElementDescription(element: PsiElement, location: ElementDescriptionLocation): String? {
        val declaration = element.asEsdmDeclaration() ?: return null

        return when (location) {
            is UsageViewTypeLocation -> declaration.kindOfEnclosingDocument()
            is UsageViewShortNameLocation, is UsageViewLongNameLocation -> declaration.textValue
            is UsageViewNodeTextLocation ->
                declaration.kindOfEnclosingDocument()?.let { "$it ${declaration.textValue}" }
                    ?: declaration.textValue

            // Anything else is left to the platform's defaults rather than guessed at.
            else -> null
        }
    }

    private fun YAMLScalar.kindOfEnclosingDocument(): String? =
        (PsiTreeUtil.getParentOfType(this, YAMLDocument::class.java)?.topLevelValue as? YAMLMapping)
            ?.textOf("kind")
}
