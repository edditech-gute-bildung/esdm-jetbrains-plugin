package net.edditech.esdm.index

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import net.edditech.esdm.schema.ESDM_FILE_SUFFIX
import org.jetbrains.yaml.structureView.YAMLCustomStructureViewFactory
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import javax.swing.Icon

/**
 * A structure view that lists artifacts rather than YAML.
 *
 * The default view shows a `---` document and then its keys, which for a file
 * holding sixteen documents is a wall of `apiVersion`, `kind`, `name`, `scope`
 * repeated sixteen times. What a reader wants is "aggregate copy, command
 * borrow, event borrowed".
 *
 * Uses the YAML plugin's own extension point for this, rather than registering a
 * competing `psiStructureViewFactory` for the whole language.
 */
class EsdmStructureViewFactory : YAMLCustomStructureViewFactory {

    override fun getStructureViewBuilder(file: YAMLFile): StructureViewBuilder? {
        if (!file.name.endsWith(ESDM_FILE_SUFFIX)) return null

        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                EsdmStructureViewModel(file)
        }
    }
}

private class EsdmStructureViewModel(file: YAMLFile) :
    StructureViewModelBase(file, EsdmFileElement(file)),
    StructureViewModel.ElementInfoProvider {

    init {
        // Document order is the file's order, which is how someone reading it
        // thinks. Alphabetical is offered but not imposed.
        withSorters(Sorter.ALPHA_SORTER)
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement) = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement) = element is EsdmDocumentElement
}

private class EsdmFileElement(private val file: YAMLFile) : StructureViewTreeElement {

    override fun getValue(): Any = file

    override fun navigate(requestFocus: Boolean) = file.navigate(requestFocus)

    override fun canNavigate(): Boolean = file.canNavigate()

    override fun canNavigateToSource(): Boolean = file.canNavigateToSource()

    override fun getPresentation(): ItemPresentation = file.presentation ?: PresentationOf(file.name, null, null)

    override fun getChildren(): Array<StructureViewTreeElement> =
        file.documents
            .mapNotNull { document -> document.asArtifact()?.let { EsdmDocumentElement(document, it) } }
            .toTypedArray()
}

/** One ESDM artifact — a whole YAML document — shown as "kind name". */
private class EsdmDocumentElement(
    private val document: YAMLDocument,
    private val artifact: Artifact,
) : StructureViewTreeElement, SortableTreeElement {

    override fun getValue(): Any = document

    override fun getAlphaSortKey(): String = artifact.name

    override fun navigate(requestFocus: Boolean) {
        // Navigates to the `name:` rather than the document start, so the caret
        // lands on the thing that was clicked.
        (artifact.anchor as? com.intellij.pom.Navigatable)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (artifact.anchor as? com.intellij.pom.Navigatable)?.canNavigate() == true

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun getPresentation(): ItemPresentation =
        PresentationOf(artifact.name, artifact.kind, null)

    override fun getChildren(): Array<StructureViewTreeElement> = emptyArray()
}

private class PresentationOf(
    private val text: String,
    private val location: String?,
    private val icon: Icon?,
) : ItemPresentation {
    override fun getPresentableText(): String = text
    override fun getLocationString(): String? = location
    override fun getIcon(unused: Boolean): Icon? = icon
}

private class Artifact(val kind: String, val name: String, val anchor: PsiElement)

/** A document is an artifact only if it declares both a kind and a name. */
private fun YAMLDocument.asArtifact(): Artifact? {
    val mapping = topLevelValue as? YAMLMapping ?: return null
    val kind = mapping.textOf("kind") ?: return null
    val nameScalar = mapping.getKeyValueByKey("name")?.value as? YAMLScalar ?: return null
    val name = nameScalar.textValue.takeIf { it.isNotEmpty() } ?: return null
    return Artifact(kind, name, nameScalar)
}
