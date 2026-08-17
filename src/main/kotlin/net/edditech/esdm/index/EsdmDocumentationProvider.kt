package net.edditech.esdm.index

import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.createSmartPointer
import com.intellij.psi.util.PsiTreeUtil
import net.edditech.esdm.schema.ESDM_FILE_SUFFIX
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

/**
 * Quick documentation for an ESDM declaration, so hovering a reference shows
 * what it points at rather than the schema's description of the field.
 *
 * Uses the current `DocumentationTarget` API; `DocumentationProvider` has been
 * deprecated since 2023.1.
 */
class EsdmDocumentationProvider : PsiDocumentationTargetProvider {

    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? {
        if (!element.containingFile.name.endsWith(ESDM_FILE_SUFFIX)) return null
        val scalar = element as? YAMLScalar ?: return null

        // Only the `name:` of a document is a declaration; every other scalar is
        // a field value or a reference, and a reference is documented by
        // whatever it resolves to.
        return if (isDeclarationName(scalar)) EsdmDeclarationTarget(scalar) else null
    }
}

/**
 * True only for the `name:` that names a whole document.
 *
 * `name` also appears nested — on invariants, timers and glossary terms — and
 * treating those as declarations made hovering an invariant describe the
 * enclosing aggregate instead.
 */
internal fun isDeclarationName(scalar: YAMLScalar): Boolean {
    val keyValue = scalar.parent as? YAMLKeyValue ?: return false
    if (keyValue.keyText != "name") return false

    val owner = keyValue.parent as? YAMLMapping ?: return false
    val topLevel = PsiTreeUtil.getParentOfType(scalar, YAMLDocument::class.java)?.topLevelValue
    return owner === topLevel
}

/**
 * Offset-based counterpart to [EsdmDocumentationProvider].
 *
 * Registering only the PSI-based provider was not enough: the JSON Schema
 * documentation contributes at this earlier stage, so it answered first and
 * every hover showed the schema's description of the field — including, for
 * anything `$ref`-ing the shared `name` definition, a note the schema authors
 * wrote to explain their own file layout.
 */
class EsdmDocumentationTargetProvider : com.intellij.platform.backend.documentation.DocumentationTargetProvider {

    override fun documentationTargets(file: com.intellij.psi.PsiFile, offset: Int): List<DocumentationTarget> {
        if (!file.name.endsWith(ESDM_FILE_SUFFIX)) return emptyList()

        val element = file.findElementAt(offset) ?: return emptyList()

        // The mouse lands on the key as often as on the value — `boundedContext`
        // in `boundedContext: cataloging` — and the key on its own has nothing
        // useful to say. Whichever half is under the cursor, the interesting
        // answer is what the value points at, so both are tried.
        val scalar = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false)
        val valueOfEnclosingPair = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false)
            ?.value as? YAMLScalar

        listOfNotNull(scalar, valueOfEnclosingPair).distinct().forEach { candidate ->
            EsdmReferences.keyFor(candidate)?.let { key ->
                EsdmReferences.resolve(file.project, key).firstOrNull()?.let {
                    return listOf(EsdmDeclarationTarget(it))
                }
            }
        }

        // Nothing resolved: fall through so the schema's own documentation is
        // shown. For a field like `kind` or `deliveryGuarantee` that is the
        // useful answer, and suppressing it would be a loss.
        return if (scalar != null && isDeclarationName(scalar)) {
            listOf(EsdmDeclarationTarget(scalar))
        } else {
            emptyList()
        }
    }
}

private class EsdmDeclarationTarget(private val nameScalar: YAMLScalar) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = nameScalar.createSmartPointer()
        return Pointer { pointer.element?.let { EsdmDeclarationTarget(it) } }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(nameScalar.textValue)
            .locationText(nameScalar.containingFile.name)
            .presentation()

    override fun computeDocumentation(): DocumentationResult? {
        val mapping = PsiTreeUtil.getParentOfType(nameScalar, YAMLDocument::class.java)
            ?.topLevelValue as? YAMLMapping
            ?: return null

        val kind = mapping.text("kind") ?: return null
        val name = mapping.text("name") ?: return null

        val html = buildString {
            append("<div class='definition'><pre>")
            append("<b>").append(kind.escaped()).append("</b> ").append(name.escaped())
            append("</pre></div>")

            append("<div class='content'>")
            mapping.text("description")?.let { append("<p>").append(it.escaped()).append("</p>") }
            append("</div>")

            val sections = buildList {
                scopeOf(mapping)?.let { add("Scope" to it) }
                // The fields that say what an artifact *does*, per kind. Read off
                // the document rather than hard-coded per kind, so a schema that
                // grows a new field still shows it.
                listOf("type", "deliveryGuarantee", "paradigm", "direction", "readModel", "identifiedBy")
                    .forEach { key -> mapping.summarise(key)?.let { add(key to it) } }
                mapping.listSummary("publishes")?.let { add("Publishes" to it) }
                mapping.listSummary("actors")?.let { add("Actors" to it) }
                mapping.namedRuleSummary("invariants")?.let { add("Invariants" to it) }
            }

            if (sections.isNotEmpty()) {
                append("<table class='sections'>")
                sections.forEach { (label, value) ->
                    append("<tr><td valign='top' class='section'><p>")
                    append(label.escaped())
                    append("</td><td valign='top'>")
                    append(value.escaped())
                    append("</td></tr>")
                }
                append("</table>")
            }
        }

        return DocumentationResult.documentation(html)
    }

    private fun scopeOf(mapping: YAMLMapping): String? {
        val scope = mapping.getKeyValueByKey("scope")?.value as? YAMLMapping ?: return null
        return listOf("domain", "boundedContext", "aggregate", "dynamicConsistencyBoundary")
            .mapNotNull { key -> scope.text(key)?.let { "$key: $it" } }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")
    }

    private fun YAMLMapping.summarise(key: String): String? {
        val value = getKeyValueByKey(key)?.value ?: return null
        return when (value) {
            is YAMLScalar -> value.textValue.takeIf { it.isNotEmpty() }
            is YAMLMapping -> value.keyValues.mapNotNull { kv ->
                (kv.value as? YAMLScalar)?.textValue?.let { "${kv.keyText}: $it" }
            }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

            else -> null
        }
    }

    private fun YAMLMapping.listSummary(key: String): String? =
        (getKeyValueByKey(key)?.value as? YAMLSequence)
            ?.items
            ?.mapNotNull { (it.value as? YAMLScalar)?.textValue }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

    private fun YAMLMapping.namedRuleSummary(key: String): String? =
        (getKeyValueByKey(key)?.value as? YAMLSequence)
            ?.items
            ?.mapNotNull { ((it.value as? YAMLMapping)?.getKeyValueByKey("name")?.value as? YAMLScalar)?.textValue }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

    private fun YAMLMapping.text(key: String): String? =
        (getKeyValueByKey(key)?.value as? YAMLScalar)?.textValue?.takeIf { it.isNotEmpty() }

    private fun String.escaped(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
