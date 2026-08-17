package net.edditech.esdm.index

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.util.ProcessingContext
import net.edditech.esdm.schema.ESDM_FILE_SUFFIX
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Makes ESDM references navigable: ctrl-click on `domain: library` goes to the
 * document that declares it, and the same for bounded contexts, commands and
 * events.
 *
 * Poly-variant because a model can legitimately be mid-edit or contain a
 * duplicate name; showing both candidates is more honest than picking one.
 */
class EsdmReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext,
                ): Array<PsiReference> {
                    if (!element.containingFile.name.endsWith(ESDM_FILE_SUFFIX)) return PsiReference.EMPTY_ARRAY
                    val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
                    if (EsdmReferences.keyFor(scalar) == null) return PsiReference.EMPTY_ARRAY

                    return arrayOf(EsdmReference(scalar))
                }
            },
        )
    }
}

class EsdmReference(scalar: YAMLScalar) : PsiPolyVariantReferenceBase<YAMLScalar>(scalar, rangeOf(scalar)) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val key = EsdmReferences.keyFor(element) ?: return ResolveResult.EMPTY_ARRAY
        return EsdmReferences.resolve(element.project, key)
            .map { com.intellij.psi.PsiElementResolveResult(it) }
            .toTypedArray()
    }

    /**
     * The declaration is a plain YAML scalar, so renaming through this reference
     * would rewrite the target's text rather than the model's identity. Rename
     * is a separate piece of work; refusing here keeps it from half-working.
     */
    override fun handleElementRename(newElementName: String): PsiElement = element

    private companion object {
        /**
         * Covers the value text only, so the highlight and click target sit on
         * `library` rather than on any surrounding quotes.
         */
        fun rangeOf(scalar: YAMLScalar): TextRange {
            val text = scalar.text
            val value = scalar.textValue
            val start = text.indexOf(value).takeIf { it >= 0 } ?: 0
            return TextRange(start, start + value.length)
        }
    }
}
