package net.edditech.esdm.index

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import net.edditech.esdm.model.EsdmModelRoot
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Renames an ESDM declaration and every reference to it.
 *
 * The default machinery would find those references through `ReferencesSearch`,
 * which searches the word index and then filters by `isReferenceTo`. That is
 * unavailable in a project opened as a bare folder — no module, no content
 * root, no index — which is the same gap that broke resolution and find-usages.
 * So the references are supplied from the model cache instead.
 *
 * Resolution is by key, never by text, which is what makes this safe: renaming
 * the `copy` aggregate does not touch an unrelated `copy` elsewhere in the
 * model, and the linter's own notion of identity is preserved.
 */
class EsdmRenameProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean = element.asEsdmDeclaration() != null

    override fun findReferences(
        element: PsiElement,
        searchScope: SearchScope,
        searchInCommentsAndStrings: Boolean,
    ): Collection<PsiReference> {
        val declaration = element.asEsdmDeclaration() ?: return emptyList()
        val origin = declaration.containingFile?.virtualFile ?: return emptyList()

        val references = EsdmModelCache.references(declaration.project, origin)

        // A declaration answers to more than one key — a bounded context is
        // addressed with a domain by a context mapping and without one by a
        // subdomain. Renaming under only one of them would leave the other
        // spelling pointing at a name that no longer exists.
        return declaration.keys()
            .flatMap { references[it].orEmpty() }
            .distinct()
            .mapNotNull { scalar -> scalar.references.firstOrNull { it is EsdmReference } }
    }

    /**
     * ESDM names are `^[a-z][a-z0-9-]*$` — the pattern the schema enforces and
     * the linter rejects. Catching it here turns a refactoring that would
     * produce an invalid model into a dialog that refuses.
     */
    override fun isToSearchInComments(element: PsiElement): Boolean = false

    override fun isToSearchForTextOccurrences(element: PsiElement): Boolean = false
}

/** Every key this declaration is known by, rebuilt from its own document. */
internal fun YAMLScalar.keys(): List<String> {
    val mapping = PsiTreeUtil.getParentOfType(this, YAMLDocument::class.java)
        ?.topLevelValue as? YAMLMapping
        ?: return emptyList()

    val kind = mapping.textOf("kind") ?: return emptyList()
    val name = textValue.takeIf { it.isNotEmpty() } ?: return emptyList()
    val scope = mapping.getKeyValueByKey("scope")?.value as? YAMLMapping

    return EsdmKeys.forDeclaration(
        kind,
        name,
        EsdmScope(
            domain = scope?.textOf("domain"),
            boundedContext = scope?.textOf("boundedContext"),
            aggregate = scope?.textOf("aggregate"),
            dynamicConsistencyBoundary = scope?.textOf("dynamicConsistencyBoundary"),
        ),
    )
}

internal fun YAMLMapping.textOf(key: String): String? =
    (getKeyValueByKey(key)?.value as? YAMLScalar)?.textValue?.takeIf { it.isNotEmpty() }
