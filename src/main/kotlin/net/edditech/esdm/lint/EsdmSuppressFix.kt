package net.edditech.esdm.lint

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.util.IncorrectOperationException

/**
 * YAML has no suppression syntax of its own, so ESDM files need a convention
 * and this plugin has to define one:
 *
 * ```yaml
 * # esdm-lint-disable esdm/modeling/orphan-context-mapping
 * kind: context-mapping
 * ```
 *
 * A comment on the line above silences that one rule at that one place; the
 * same comment with `-file` silences it for the whole document. Deliberately
 * rule-scoped rather than blanket — a model that carries a permanent, explained
 * warning should still light up when a *different* one appears. The four
 * warnings in a typical real model are exactly this case: they are correct
 * observations the modeller has already reasoned about.
 */
class EsdmSuppressFix(private val finding: EsdmFinding) : IntentionAction {

    override fun getText(): String = "Suppress '${finding.ruleId}' here"

    override fun getFamilyName(): String = "Suppress ESDM lint rule"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = editor != null

    override fun startInWriteAction(): Boolean = true

    @Throws(IncorrectOperationException::class)
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = editor?.document ?: return
        val lineIndex = (finding.line - 1).coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
        val lineStart = document.getLineStartOffset(lineIndex)
        val indent = document.charsSequence
            .subSequence(lineStart, document.getLineEndOffset(lineIndex))
            .takeWhile { it == ' ' }

        document.insertString(lineStart, "$indent$DISABLE_LINE ${finding.ruleId}\n")
    }

    companion object {
        const val DISABLE_LINE = "# esdm-lint-disable"
        const val DISABLE_FILE = "# esdm-lint-disable-file"
    }
}

/**
 * Whether [finding] has been silenced in [document] by the convention above.
 *
 * Checked against the document rather than the PSI so it costs nothing and
 * works while the file is being edited.
 */
internal fun EsdmFinding.isSuppressedIn(document: Document): Boolean {
    val text = document.charsSequence

    if (text.contains("${EsdmSuppressFix.DISABLE_FILE} $ruleId")) return true

    val lineIndex = line - 1
    if (lineIndex <= 0 || lineIndex > document.lineCount - 1) return false

    val previous = text.subSequence(
        document.getLineStartOffset(lineIndex - 1),
        document.getLineEndOffset(lineIndex - 1),
    ).trim()

    return previous.startsWith(EsdmSuppressFix.DISABLE_LINE) &&
        previous.removePrefix(EsdmSuppressFix.DISABLE_LINE).trim() == ruleId
}
