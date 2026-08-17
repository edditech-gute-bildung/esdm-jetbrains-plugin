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
        val keyValue = scalar.parent as? YAMLKeyValue ?: return null
        if (keyValue.keyText != "name") return null

        return EsdmDeclarationTarget(scalar)
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
