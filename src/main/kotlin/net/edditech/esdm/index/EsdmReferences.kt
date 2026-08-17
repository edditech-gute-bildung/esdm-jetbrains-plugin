package net.edditech.esdm.index

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import net.edditech.esdm.model.EsdmModelRoot
import org.jetbrains.yaml.psi.YAMLFile
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

        // Both halves of a key-value are children of it, so without this the key
        // itself would be read as a reference to something named after the key.
        if (keyValue.value !== scalar) return null

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
     *
     * @param context any element in the referring file, used to locate the model
     *   when the index cannot answer.
     */
    fun resolve(project: Project, key: String, context: PsiElement? = null): List<YAMLScalar> {
        val fromIndex = fromIndex(project, key)
        if (fromIndex.isNotEmpty()) return fromIndex

        return context?.let { resolveByScanning(project, key, it) }.orEmpty()
    }

    private fun fromIndex(project: Project, key: String): List<YAMLScalar> {
        val manager = PsiManager.getInstance(project)
        val results = mutableListOf<YAMLScalar>()

        FileBasedIndex.getInstance().processValues(
            EsdmDeclarationIndex.KEY,
            key,
            null,
            { file, offset ->
                val element = manager.findFile(file)?.findElementAt(offset)
                PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java)?.let { results += it }
                true
            },
            GlobalSearchScope.allScope(project),
        )
        return results
    }

    /**
     * Fallback for models the index never saw.
     *
     * A file-based index only covers files in an indexable set — content roots,
     * libraries and the like. Opening a model directory as a bare folder gives a
     * project with no module and no content root, and then the index is
     * permanently empty: every reference reported "Cannot find declaration to go
     * to" while the tests passed, because a test fixture always has a module.
     *
     * Scanning is affordable here in a way it would not be for a general
     * language: a model is tens of files, and the directory is already known
     * because the linter runs from it.
     */
    @org.jetbrains.annotations.VisibleForTesting
    fun resolveByScanning(project: Project, key: String, context: PsiElement): List<YAMLScalar> {
        val origin = context.containingFile?.virtualFile ?: return emptyList()
        val root = EsdmModelRoot.of(origin) ?: return emptyList()
        val manager = PsiManager.getInstance(project)

        return EsdmModelRoot.documentsUnder(root).flatMap { file ->
            val yaml = manager.findFile(file) as? YAMLFile ?: return@flatMap emptyList()
            yaml.documents.mapNotNull { document -> document.declarationMatching(key) }
        }
    }

    /** The document's `name:` scalar, if this document declares [key]. */
    private fun YAMLDocument.declarationMatching(key: String): YAMLScalar? {
        val mapping = topLevelValue as? YAMLMapping ?: return null
        val kind = mapping.text("kind") ?: return null
        val nameScalar = mapping.getKeyValueByKey("name")?.value as? YAMLScalar ?: return null
        val name = nameScalar.textValue.takeIf { it.isNotEmpty() } ?: return null

        val scope = mapping.getKeyValueByKey("scope")?.value as? YAMLMapping
        val declaredIn = EsdmScope(
            domain = scope?.text("domain"),
            boundedContext = scope?.text("boundedContext"),
            aggregate = scope?.text("aggregate"),
            dynamicConsistencyBoundary = scope?.text("dynamicConsistencyBoundary"),
        )

        // Deliberately the same key construction the index uses, so the fallback
        // cannot resolve differently from the fast path.
        return if (key in EsdmKeys.forDeclaration(kind, name, declaredIn)) nameScalar else null
    }

    private fun YAMLMapping.text(key: String): String? =
        (getKeyValueByKey(key)?.value as? YAMLScalar)?.textValue?.takeIf { it.isNotEmpty() }
}
