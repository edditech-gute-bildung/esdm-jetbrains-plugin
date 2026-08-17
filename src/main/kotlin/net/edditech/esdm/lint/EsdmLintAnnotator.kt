package net.edditech.esdm.lint

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import net.edditech.esdm.schema.ESDM_FILE_SUFFIX

/**
 * Surfaces `esdm lint` findings inline.
 *
 * The three phases run under different constraints, and getting them wrong is
 * the classic bug in this API: [collectInformation] holds a read action,
 * [doAnnotate] holds none and may take as long as a process needs, and [apply]
 * holds a read action again. Nothing PSI-shaped may be carried between them —
 * elements can be invalidated in the gap — so phase one snapshots plain data.
 */
class EsdmLintAnnotator : ExternalAnnotator<EsdmLintAnnotator.Request, EsdmLintAnnotator.Response>(), DumbAware {

    data class Request(val file: PsiFile, val modificationStamp: Long)

    data class Response(val findings: List<EsdmFinding>, val modificationStamp: Long)

    override fun collectInformation(file: PsiFile): Request? {
        if (!file.name.endsWith(ESDM_FILE_SUFFIX)) return null
        if (file.virtualFile == null) return null

        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        return Request(file, document.modificationStamp)
    }

    override fun doAnnotate(collectedInfo: Request?): Response? {
        val request = collectedInfo ?: return null
        val virtualFile = request.file.virtualFile ?: return null

        val result = EsdmLintService.getInstance(request.file.project).findingsFor(virtualFile) ?: return null

        // A missing binary is the normal state of a fresh checkout, and a failing
        // one usually means a misconfigured path. Neither is the file's fault,
        // so neither is reported as a problem in the file.
        return when (result.outcome) {
            is EsdmCli.Outcome.NotInstalled, is EsdmCli.Outcome.Failed -> null
            is EsdmCli.Outcome.Findings -> Response(result.findings, request.modificationStamp)
        }
    }

    override fun apply(file: PsiFile, annotationResult: Response?, holder: AnnotationHolder) {
        val response = annotationResult ?: return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return

        // The document may have moved on while the process ran. Rather than
        // annotate at offsets that no longer mean anything, drop the pass; the
        // next one runs against the edited text.
        if (document.modificationStamp != response.modificationStamp) return

        for (finding in response.findings) {
            if (finding.isSuppressedIn(document)) continue
            val range = finding.rangeIn(document) ?: continue
            holder.newAnnotation(finding.severity.toHighlightSeverity(), finding.message)
                .range(range)
                .withFix(EsdmSuppressFix(finding))
                .create()
        }
    }

    private fun EsdmSeverity.toHighlightSeverity(): HighlightSeverity = when (this) {
        EsdmSeverity.ERROR -> HighlightSeverity.ERROR
        EsdmSeverity.WARNING -> HighlightSeverity.WARNING
    }
}

/**
 * Maps a 1-based line/column with no end position onto a range in [document].
 *
 * Everything is clamped: an out-of-range [TextRange] throws and takes the whole
 * highlighting pass down with it, and the CLI's view of the file can legitimately
 * be a moment behind the editor's.
 */
internal fun EsdmFinding.rangeIn(document: Document): TextRange? {
    val lineIndex = (line - 1).coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
    if (document.textLength == 0) return null

    val lineStart = document.getLineStartOffset(lineIndex)
    val lineEnd = document.getLineEndOffset(lineIndex)
    val start = (lineStart + (column - 1).coerceAtLeast(0)).coerceIn(lineStart, lineEnd)

    // The CLI points at a token but reports no end, so highlight from there to
    // the end of the line. An empty line still needs a non-empty range or the
    // annotation is invisible, hence falling back to the whole line.
    val end = if (start < lineEnd) lineEnd else lineEnd.coerceAtLeast(lineStart)
    return if (start >= end) {
        if (lineStart == lineEnd) null else TextRange(lineStart, lineEnd)
    } else {
        TextRange(start, end)
    }
}
