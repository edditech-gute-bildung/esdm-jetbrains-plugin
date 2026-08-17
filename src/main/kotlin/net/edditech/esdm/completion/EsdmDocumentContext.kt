package net.edditech.esdm.completion

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue

/**
 * Where the caret sits inside one ESDM document, expressed in terms the schema
 * can answer questions about.
 *
 * Scoped to the enclosing [YAMLDocument] rather than the file: an `.esdm.yaml`
 * file holds up to sixteen documents in practice, each with its own
 * `apiVersion`, so file-level context would answer for the wrong artifact.
 */
data class EsdmDocumentContext(
    /** The document's contents, deep enough to evaluate schema discriminators. */
    val data: Map<String, Any?>,
    /** Keys from the document root down to the caret. */
    val path: List<String>,
    /** True when completing a value (`kind: <caret>`), false when completing a key. */
    val inValuePosition: Boolean,
    /** Keys already written in the mapping being completed, so they are not offered twice. */
    val siblings: Set<String>,
) {
    companion object {

        fun of(position: PsiElement): EsdmDocumentContext? {
            val document = PsiTreeUtil.getParentOfType(position, YAMLDocument::class.java) ?: return null
            val topLevel = document.topLevelValue as? YAMLMapping ?: return null

            val enclosingKeyValue = PsiTreeUtil.getParentOfType(position, YAMLKeyValue::class.java)
            val inValuePosition = enclosingKeyValue?.isCaretInValue(position) ?: false

            val keyChain = generateSequence(enclosingKeyValue) {
                PsiTreeUtil.getParentOfType(it, YAMLKeyValue::class.java)
            }.mapNotNull { it.keyText.takeIf(String::isNotEmpty) }.toList().reversed()

            // Completing a key means the enclosing key-value is the half-typed
            // key itself, so it is not part of the path to the containing object.
            val path = if (inValuePosition) keyChain else keyChain.dropLast(1)

            val containingMapping = if (inValuePosition) {
                enclosingKeyValue?.value as? YAMLMapping
            } else {
                PsiTreeUtil.getParentOfType(position, YAMLMapping::class.java)
            }

            return EsdmDocumentContext(
                data = topLevel.toMap(),
                path = path,
                inValuePosition = inValuePosition,
                siblings = containingMapping?.keyValues?.mapNotNull { it.keyText }?.toSet().orEmpty(),
            )
        }

        private fun YAMLKeyValue.isCaretInValue(position: PsiElement): Boolean {
            val valueRange = value?.textRange ?: return false
            return valueRange.contains(position.textRange.startOffset)
        }

        /**
         * Only the shape the schema needs to discriminate on: nested mappings,
         * sequences, and scalars as text. Anything else collapses to a
         * placeholder, since its value never decides a branch.
         */
        private fun YAMLMapping.toMap(): Map<String, Any?> =
            keyValues.mapNotNull { keyValue ->
                val key = keyValue.keyText.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                key to keyValue.value?.toData()
            }.toMap()

        private fun YAMLValue.toData(): Any? = when (this) {
            is YAMLMapping -> toMap()
            is YAMLSequence -> items.mapNotNull { it.value?.toData() }
            is YAMLScalar -> textValue
            else -> null
        }
    }
}
