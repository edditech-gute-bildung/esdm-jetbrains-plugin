package net.edditech.esdm.index

import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.RenameInputValidator
import com.intellij.util.ProcessingContext

/**
 * Refuses a rename that would produce a name the schema forbids.
 *
 * ESDM names match `^[a-z][a-z0-9-]*$` — the core schema's `$defs/name`. Typing
 * `Copy` or `copy_2` in the rename dialog would otherwise apply cleanly and
 * leave a model that `esdm lint` rejects, with the damage spread across every
 * file that referenced it. Failing in the dialog is cheaper than failing after.
 */
class EsdmNamesValidator : RenameInputValidator {

    override fun getPattern(): ElementPattern<out PsiElement> = PlatformPatterns.psiElement()

    override fun isInputValid(newName: String, element: PsiElement, context: ProcessingContext): Boolean {
        if (element.asEsdmDeclaration() == null) return true
        return ESDM_NAME.matches(newName)
    }

    private companion object {
        /** Kept in step with `$defs/name` in the core schema. */
        val ESDM_NAME = Regex("^[a-z][a-z0-9-]*$")
    }
}
