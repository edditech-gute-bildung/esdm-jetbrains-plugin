package net.edditech.esdm.index

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile

/**
 * The structure view for an ESDM file, taken through the same factory the IDE
 * consults rather than by constructing the model directly.
 */
class EsdmStructureViewTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    private fun structureOf(relativePath: String): List<String> {
        val root = myFixture.copyDirectoryToProject("library", "library")
        myFixture.openFileInEditor(root.findFileByRelativePath(relativePath)!!)

        val builder = EsdmStructureViewFactory().getStructureViewBuilder(myFixture.file as YAMLFile)
        assertNotNull("no structure view offered for $relativePath", builder)

        val model = (builder as com.intellij.ide.structureView.TreeBasedStructureViewBuilder)
            .createStructureViewModel(myFixture.editor)

        return model.root.children.map { child ->
            val presentation = (child as StructureViewTreeElement).presentation
            "${presentation.locationString} ${presentation.presentableText}"
        }
    }

    /**
     * A file with eight documents should read as eight artifacts, not as eight
     * repetitions of apiVersion/kind/name/scope.
     */
    fun testArtifactsAreListedRatherThanYamlKeys() {
        val structure = structureOf("lending/copy.esdm.yaml")

        assertEquals(
            listOf(
                "bounded-context lending",
                "aggregate copy",
                "command stock",
                "event stocked",
                "command borrow",
                "event borrowed",
                "command return",
                "event returned",
            ),
            structure,
        )
    }

    /** Document order is the file's order — how someone reading it thinks. */
    fun testOrderFollowsTheFile() {
        val structure = structureOf("cataloging/book.esdm.yaml")

        assertEquals(
            listOf("bounded-context cataloging", "aggregate book", "event acquired", "command acquire"),
            structure,
        )
    }

    /** A feature document declares a kind and a name too, so it belongs in the tree. */
    fun testFeatureFilesAreListed() {
        assertEquals(listOf("feature borrowing-a-copy"), structureOf("lending/copy.feature.esdm.yaml"))
    }

    /** Only ESDM files get the custom view; ordinary YAML keeps the default one. */
    fun testPlainYamlIsLeftAlone() {
        myFixture.configureByText("compose.yaml", "services:\n  app:\n    image: nginx\n")
        assertNull(EsdmStructureViewFactory().getStructureViewBuilder(myFixture.file as YAMLFile))
    }
}
