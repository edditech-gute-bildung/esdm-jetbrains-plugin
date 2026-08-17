package net.edditech.esdm.index

import com.intellij.pom.PomDeclarationSearcher
import com.intellij.pom.PomTarget
import com.intellij.pom.PomNamedTarget
import com.intellij.psi.DelegatePsiTarget
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Consumer
import net.edditech.esdm.schema.ESDM_FILE_SUFFIX
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Presents an ESDM declaration as a named symbol.
 *
 * Without this the platform has nothing to grab hold of. A declaration is a
 * plain YAML scalar owned by the YAML plugin, and `TargetElementUtil` only
 * accepts a `PsiNamedElement` when the caret sits on a declaration — so Find
 * Usages worked from a reference, where the platform resolves first, and did
 * nothing at all from the declaration, which is the more natural place to
 * invoke it.
 *
 * A `PomTarget` is the platform's answer for exactly this: a symbol that is not
 * a PSI element of its own. The YAML plugin uses the same mechanism for scalar
 * keys, so the precedent is its own.
 */
class EsdmDeclarationSearcher : PomDeclarationSearcher() {

    override fun findDeclarationsAt(element: PsiElement, offsetInElement: Int, consumer: Consumer<in PomTarget>) {
        if (element.containingFile?.name?.endsWith(ESDM_FILE_SUFFIX) != true) return

        // The caret lands on a leaf; the declaration is the scalar above it.
        val scalar = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false) ?: return
        if (!isDeclarationName(scalar)) return

        consumer.consume(EsdmPomTarget(scalar))
    }
}

/**
 * Named symbol for a declaration, delegating navigation to the scalar itself so
 * "go to declaration" lands on the name rather than the document.
 */
class EsdmPomTarget(val scalar: YAMLScalar) :
    DelegatePsiTarget(scalar),
    PomNamedTarget,
    com.intellij.pom.PomRenameableTarget<Any?> {

    override fun getName(): String = scalar.textValue

    override fun isWritable(): Boolean = scalar.isValid && scalar.isWritable

    /**
     * Renames the declaration itself. References are rewritten separately, by
     * the rename processor — the platform drives both halves.
     *
     * Goes through the element manipulator rather than editing text directly:
     * `YAMLScalarElementManipulator` knows how to replace a scalar's value
     * whatever its YAML flavour, which a naive text splice would get wrong for
     * quoted or block scalars.
     */
    override fun setName(newName: String): Any? {
        com.intellij.psi.ElementManipulators.handleContentChange(scalar, newName)
        return this
    }

    override fun equals(other: Any?): Boolean = other is EsdmPomTarget && other.scalar == scalar

    override fun hashCode(): Int = scalar.hashCode()
}

/** The declaration behind whatever the platform handed us, PSI or POM. */
internal fun PsiElement.asEsdmDeclaration(): YAMLScalar? = when {
    this is com.intellij.pom.PomTargetPsiElement -> (target as? EsdmPomTarget)?.scalar
    this is YAMLScalar && isDeclarationName(this) -> this
    else -> null
}
