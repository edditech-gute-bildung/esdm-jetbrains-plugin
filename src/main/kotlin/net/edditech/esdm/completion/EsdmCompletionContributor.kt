package net.edditech.esdm.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.project.DumbAware
import net.edditech.esdm.schema.ESDM_FILE_SUFFIX
import net.edditech.esdm.schema.EsdmSchema

/**
 * Replaces the platform's schema completion inside `*.esdm.yaml`.
 *
 * Replaces rather than supplements, because the platform's suggestions are not
 * merely incomplete — they are wrong. Inside a `kind: aggregate` it offers all
 * fifty properties from every kind in every branch, and for a
 * `context-mapping`'s `type` it offers the *actor* enum. Adding correct entries
 * alongside wrong ones would leave the wrong ones on screen, so this contributor
 * runs first and calls `stopHere()`.
 *
 * See EsdmSchemaTest for the measured behaviour, and IJPL-63615 upstream.
 */
class EsdmCompletionContributor : CompletionContributor(), DumbAware {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (!file.name.endsWith(ESDM_FILE_SUFFIX)) return

        val context = EsdmDocumentContext.of(parameters.position) ?: return
        // An unrecognised apiVersion means this is not a document we understand.
        // Better to leave the platform's behaviour alone than to guess at it.
        if (context.data[EsdmSchema.API_VERSION] !in EsdmSchema.apiVersions) return

        // The schema, not the PSI shape, decides what belongs here. YAML parses
        // the caret in `scope:\n  <caret>` as a scalar *value* of `scope`, even
        // though the user is starting a nested key — so asking only for enum
        // values there would find none and hand the position back to the
        // platform's incorrect completion. Falling through to properties covers
        // both readings without having to out-guess the parser.
        val values = if (context.inValuePosition) {
            EsdmSchema.valuesAt(context.data, context.path)
        } else {
            emptyList()
        }

        val elements = if (values.isNotEmpty()) {
            values.map(::valueElement)
        } else {
            EsdmSchema.propertiesAt(context.data, context.path)
                .filterNot { it.name in context.siblings }
                .map(::propertyElement)
        }

        if (elements.isEmpty()) return

        result.addAllElements(elements)
        result.stopHere()
    }

    private fun propertyElement(property: EsdmSchema.Property): LookupElement {
        val element = LookupElementBuilder.create(property.name)
            .withTypeText(property.type)
            .withTailText(property.description?.firstSentence()?.let { "  $it" }, true)
            .withBoldness(property.required)
            .withInsertHandler { context, _ -> insertKey(context) }

        // Required properties first: in a kind with a dozen optional fields,
        // the two the linter will insist on should not be buried alphabetically.
        return PrioritizedLookupElement.withPriority(element, if (property.required) 1.0 else 0.0)
    }

    private fun valueElement(value: EsdmSchema.Value): LookupElement =
        LookupElementBuilder.create(value.value)
            .withTailText(value.description?.firstSentence()?.let { "  $it" }, true)

    /** Keys are always followed by `: `, so type it for the user. */
    private fun insertKey(context: InsertionContext) {
        val document = context.document
        val tail = document.charsSequence.subSequence(
            context.tailOffset,
            minOf(context.tailOffset + 2, document.textLength),
        )
        if (!tail.startsWith(":")) {
            EditorModificationUtil.insertStringAtCaret(context.editor, ": ")
        }
    }

    private fun String.firstSentence(): String {
        val cleaned = replace('\n', ' ').trim()
        val end = cleaned.indexOf(". ")
        val sentence = if (end > 0) cleaned.take(end + 1) else cleaned
        return if (sentence.length > MAX_TAIL) sentence.take(MAX_TAIL).trimEnd() + "…" else sentence
    }

    private companion object {
        const val MAX_TAIL = 90
    }
}
