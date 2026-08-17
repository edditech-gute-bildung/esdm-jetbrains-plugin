package net.edditech.esdm.index

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import net.edditech.esdm.schema.ESDM_FILE_SUFFIX
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Gutter icons linking the two halves of the write side: a command to the events
 * it publishes, and an event back to whatever produces and consumes it.
 *
 * Reading a model, the question is almost always "and then what?" — the answer
 * lives in another file, and following it by hand means knowing the reference
 * shapes. One click is better.
 */
class EsdmLineMarkerProvider : RelatedItemLineMarkerProvider(), DumbAware {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        // Leaf elements only. A marker on a composite makes the line-markers
        // pass flicker, which the platform documents as a hard rule.
        if (element.firstChild != null) return
        if (element.containingFile?.name?.endsWith(ESDM_FILE_SUFFIX) != true) return

        val scalar = element.parent as? YAMLScalar ?: return
        if (!isDeclarationName(scalar)) return

        val mapping = PsiTreeUtil.getParentOfType(scalar, YAMLDocument::class.java)
            ?.topLevelValue as? YAMLMapping
            ?: return

        // The marker must be anchored on the leaf that was passed in, not on the
        // scalar above it: anchoring on a composite makes the line-markers pass
        // recompute and flicker, and the platform asserts on it in tests.
        when (mapping.textOf("kind")) {
            "command" -> markCommand(element, mapping, result)
            "event" -> markEvent(element, scalar, result)
        }
    }

    /** A command's `publishes` names events; each is a reference we can resolve. */
    private fun markCommand(
        anchor: PsiElement,
        mapping: YAMLMapping,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        val published = (mapping.getKeyValueByKey("publishes")?.value as? org.jetbrains.yaml.psi.YAMLSequence)
            ?.items
            ?.mapNotNull { it.value as? YAMLScalar }
            ?.flatMap { entry ->
                EsdmReferences.keyFor(entry)
                    ?.let { EsdmReferences.resolve(anchor.project, it, entry) }
                    .orEmpty()
            }
            .orEmpty()

        if (published.isEmpty()) return

        result.add(
            NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
                .setTargets(published)
                .setTooltipText("Publishes ${published.joinToString(", ") { it.textValue }}")
                .createLineMarkerInfo(anchor),
        )
    }

    /**
     * For an event, everything pointing at it: the command that publishes it,
     * and the policies, handlers and read models that consume it. Each referring
     * scalar is reported as its enclosing document, since "the policy" is more
     * useful to navigate to than the line inside it.
     */
    private fun markEvent(
        anchor: PsiElement,
        declaration: YAMLScalar,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        val origin = declaration.containingFile?.virtualFile ?: return
        val references = EsdmModelCache.references(declaration.project, origin)

        val related = declaration.keys()
            .flatMap { references[it].orEmpty() }
            .mapNotNull { it.owningDeclaration() }
            .filter { it != declaration }
            .distinct()

        if (related.isEmpty()) return

        result.add(
            NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridenMethod)
                .setTargets(related)
                .setTooltipText("Produced or consumed by ${related.joinToString(", ") { it.describe() }}")
                .createLineMarkerInfo(anchor),
        )
    }

    /** The `name:` scalar of the document this element sits in. */
    private fun YAMLScalar.owningDeclaration(): YAMLScalar? =
        (PsiTreeUtil.getParentOfType(this, YAMLDocument::class.java)?.topLevelValue as? YAMLMapping)
            ?.getKeyValueByKey("name")
            ?.value as? YAMLScalar

    private fun YAMLScalar.describe(): String {
        val kind = (PsiTreeUtil.getParentOfType(this, YAMLDocument::class.java)?.topLevelValue as? YAMLMapping)
            ?.textOf("kind")
        return if (kind != null) "$kind ${textValue}" else textValue
    }
}
