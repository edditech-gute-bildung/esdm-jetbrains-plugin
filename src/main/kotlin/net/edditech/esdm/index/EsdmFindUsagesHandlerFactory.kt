package net.edditech.esdm.index

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import net.edditech.esdm.schema.ESDM_FILE_SUFFIX
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Find Usages for ESDM declarations — "where is this event handled?", which is
 * the question a model raises constantly.
 *
 * A handler factory rather than a `FindUsagesProvider`, for two reasons. The
 * YAML plugin already registers the one provider its language is allowed, so
 * there is no slot to take. And the default machinery would search by text and
 * then ask each candidate reference `isReferenceTo`, which needs the word index
 * — unavailable in a project opened as a bare folder, the same gap that broke
 * resolution. Walking the model directly sidesteps both.
 */
class EsdmFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    // Accepts both spellings of the same thing: the scalar itself when the
    // platform resolved a reference to it, and the PomTargetPsiElement when the
    // caret is on the declaration.
    override fun canFindUsages(element: PsiElement): Boolean = element.asEsdmDeclaration() != null

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? =
        element.asEsdmDeclaration()?.let { EsdmFindUsagesHandler(element, it) }
}

private class EsdmFindUsagesHandler(
    psiElement: PsiElement,
    private val declaration: YAMLScalar,
) : FindUsagesHandler(psiElement) {

    override fun processElementUsages(
        element: PsiElement,
        processor: Processor<in UsageInfo>,
        options: FindUsagesOptions,
    ): Boolean {
        val usages = ReadAction.compute<List<YAMLScalar>, RuntimeException> {
            val origin = declaration.containingFile?.virtualFile
                ?: return@compute emptyList()

            // A declaration is reachable under several keys — a bounded context
            // is addressed with a domain by a context mapping and without one by
            // a subdomain — so every key it was indexed under has to be asked
            // about, or a whole class of usage goes missing.
            val references = EsdmModelCache.references(declaration.project, origin)
            declarationKeys().flatMap { references[it].orEmpty() }.distinct()
        }

        return usages.all { usage -> processor.process(UsageInfo(usage)) }
    }

    /** The keys this declaration is known by, rebuilt from its own document. */
    private fun declarationKeys(): List<String> {
        val mapping = PsiTreeUtil.getParentOfType(declaration, YAMLDocument::class.java)
            ?.topLevelValue as? YAMLMapping
            ?: return emptyList()

        val kind = mapping.text("kind") ?: return emptyList()
        val name = declaration.textValue.takeIf { it.isNotEmpty() } ?: return emptyList()
        val scope = mapping.getKeyValueByKey("scope")?.value as? YAMLMapping

        return EsdmKeys.forDeclaration(
            kind,
            name,
            EsdmScope(
                domain = scope?.text("domain"),
                boundedContext = scope?.text("boundedContext"),
                aggregate = scope?.text("aggregate"),
                dynamicConsistencyBoundary = scope?.text("dynamicConsistencyBoundary"),
            ),
        )
    }

    private fun YAMLMapping.text(key: String): String? =
        (getKeyValueByKey(key)?.value as? YAMLScalar)?.textValue?.takeIf { it.isNotEmpty() }
}
