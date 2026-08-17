package net.edditech.esdm.index

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import net.edditech.esdm.schema.ESDM_FILE_SUFFIX
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * ESDM documentation in the *legacy* provider chain, deliberately.
 *
 * The YAML plugin contributes schema documentation as
 * `lang.documentationProvider` — `YamlJsonSchemaDocumentationProvider`. That is
 * a separate chain from the current `DocumentationTarget` API, and a new-style
 * provider cannot outrank it however it is ordered: registering ours as
 * `platform.backend.documentation.targetProvider` with `order="first"` made the
 * integration test pass while the IDE still showed the schema's answer, because
 * the test reached the new-style API and the hover popup reached this one.
 *
 * `DocumentationProvider` has been deprecated since 2023.1, so this is not a
 * default worth copying. Competing with a legacy provider means joining its
 * chain; the new-style providers stay registered for the paths that use them.
 *
 * Returning null falls through to the schema provider, which is wanted: for
 * `kind` or `deliveryGuarantee` the schema's description is the useful reply.
 */
class EsdmLegacyDocumentationProvider : AbstractDocumentationProvider() {

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val candidates = listOfNotNull(element, originalElement)
        if (candidates.none { it.containingFile?.name?.endsWith(ESDM_FILE_SUFFIX) == true }) return null

        // When a reference resolves, the platform hands us the target as
        // `element` and the scalar under the cursor as `originalElement`.
        candidates.firstNotNullOfOrNull { it.asDeclarationName() }
            ?.let { return EsdmDeclarationHtml.of(it) }

        // Otherwise resolve whatever is under the cursor — either half of the
        // key-value, since the mouse lands on both.
        return candidates.firstNotNullOfOrNull { candidate -> candidate.resolveEsdmTarget() }
            ?.let { EsdmDeclarationHtml.of(it) }
    }

    private fun PsiElement.asDeclarationName(): YAMLScalar? =
        PsiTreeUtil.getParentOfType(this, YAMLScalar::class.java, false)
            ?.takeIf { isDeclarationName(it) }

    private fun PsiElement.resolveEsdmTarget(): YAMLScalar? {
        val scalar = PsiTreeUtil.getParentOfType(this, YAMLScalar::class.java, false)
        val valueOfPair = PsiTreeUtil.getParentOfType(this, YAMLKeyValue::class.java, false)?.value as? YAMLScalar

        return listOfNotNull(scalar, valueOfPair).distinct().firstNotNullOfOrNull { candidate ->
            EsdmReferences.keyFor(candidate)?.let { key ->
                EsdmReferences.resolve(candidate.project, key).firstOrNull()
            }
        }
    }
}
