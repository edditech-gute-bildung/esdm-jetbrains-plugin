package net.edditech.esdm.index

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Works out what a scalar in an ESDM document refers to.
 *
 * Reference shapes are positional rather than syntactic — `boundedContext`
 * means something different inside a `scope` than inside an `eventReference` —
 * so each site is identified by its key together with its siblings, which is
 * exactly how the schema defines them.
 */
object EsdmReferences {

    /** The index key this scalar refers to, or null when it is not a reference. */
    fun keyFor(scalar: YAMLScalar): String? {
        val value = scalar.textValue.takeIf { it.isNotEmpty() } ?: return null
        val keyValue = scalar.parent as? YAMLKeyValue
            ?: return sequenceItemKey(scalar, value)

        val siblings = keyValue.parent as? YAMLMapping ?: return null

        return when (keyValue.keyText) {
            "domain" -> EsdmKeys.domain(value)

            "boundedContext" -> {
                // Inside a scope or a context-mapping endpoint the sibling
                // `domain` qualifies it; inside an event or command reference
                // there is no domain to qualify with.
                val domain = siblings.text("domain")
                if (domain != null) EsdmKeys.boundedContext(domain, value) else EsdmKeys.boundedContextAnyDomain(value)
            }

            "aggregate", "dynamicConsistencyBoundary" -> {
                // In a `scope` this addresses the unit the document belongs to;
                // in a reference it names the owner. Both resolve the same way.
                val boundedContext = siblings.text("boundedContext") ?: return null
                EsdmKeys.consistencyUnit(boundedContext, value)
            }

            // The heart of the two eventReference variants: the presence or
            // absence of a sibling `aggregate` decides which event is meant, and
            // a pair must not find an aggregate-owned event.
            "event" -> {
                val boundedContext = siblings.text("boundedContext") ?: return null
                EsdmKeys.event(boundedContext, siblings.text("aggregate"), value)
            }

            "command" -> {
                val boundedContext = siblings.text("boundedContext") ?: return null
                val owner = siblings.text("aggregate")
                    ?: siblings.text("dynamicConsistencyBoundary")
                    ?: return null
                EsdmKeys.command(boundedContext, owner, value)
            }

            "readModel" -> null // resolved once read-models are indexed

            else -> null
        }
    }

    /**
     * Bare names in a sequence, where the enclosing document's own scope supplies
     * the context the entry omits — `command.publishes` names events produced by
     * the very unit the command belongs to.
     */
    private fun sequenceItemKey(scalar: YAMLScalar, value: String): String? {
        val item = scalar.parent as? YAMLSequenceItem ?: return null
        val listKey = PsiTreeUtil.getParentOfType(item, YAMLKeyValue::class.java) ?: return null
        val scope = enclosingScope(scalar) ?: return null

        return when (listKey.keyText) {
            "publishes" -> scope.boundedContext?.let { EsdmKeys.event(it, scope.aggregate, value) }
            "boundedContexts" -> scope.domain?.let { EsdmKeys.boundedContext(it, value) }
            else -> null
        }
    }

    /** The `scope` of the document the element sits in. */
    fun enclosingScope(element: PsiElement): EsdmScope? {
        val document = PsiTreeUtil.getParentOfType(element, YAMLDocument::class.java) ?: return null
        val scope = (document.topLevelValue as? YAMLMapping)
            ?.getKeyValueByKey("scope")?.value as? YAMLMapping
            ?: return null

        return EsdmScope(
            domain = scope.text("domain"),
            boundedContext = scope.text("boundedContext"),
            aggregate = scope.text("aggregate"),
            dynamicConsistencyBoundary = scope.text("dynamicConsistencyBoundary"),
        )
    }

    /**
     * The `name:` scalar of every document declaring [key]. Normally one; more
     * than one means the model has a duplicate, which the linter reports and
     * this deliberately does not hide.
     */
    fun resolve(project: Project, key: String): List<YAMLScalar> {
        val index = FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.allScope(project)
        val manager = PsiManager.getInstance(project)
        val results = mutableListOf<YAMLScalar>()

        index.processValues(EsdmDeclarationIndex.KEY, key, null, { file, offset ->
            val psiFile = manager.findFile(file)
            val element = psiFile?.findElementAt(offset)
            PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java)?.let { results += it }
            true
        }, scope)

        return results
    }

    private fun YAMLMapping.text(key: String): String? =
        (getKeyValueByKey(key)?.value as? YAMLScalar)?.textValue?.takeIf { it.isNotEmpty() }
}
