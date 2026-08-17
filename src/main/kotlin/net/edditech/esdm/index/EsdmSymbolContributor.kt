package net.edditech.esdm.index

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import net.edditech.esdm.model.EsdmModelRoot
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Go to Symbol for ESDM declarations, so an event can be reached by name without
 * knowing which bounded context owns it.
 *
 * `ChooseByNameContributorEx` rather than the older `ChooseByNameContributor`:
 * the Ex variant is scope-aware and streams through a processor instead of
 * building the whole list, which is what the platform expects of an index-backed
 * contributor.
 *
 * Sources the names by walking the project rather than the index. Go to Symbol
 * is project-wide with no file to start from, and a project opened as a bare
 * folder has no index at all — the gap that already broke resolution,
 * find-usages and rename. Walking is affordable because a model is tens of
 * files, and results are cached per modification.
 */
class EsdmSymbolContributor : ChooseByNameContributorEx {

    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        val project = scope.project ?: return
        declarations(project).keys.forEach { name ->
            if (!processor.process(name)) return
        }
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters,
    ) {
        declarations(parameters.project)[name].orEmpty().forEach { scalar ->
            // The POM element is what carries a presentable name and icon; the
            // bare scalar would show up as an unnamed YAML node.
            val item = com.intellij.pom.references.PomService.convertToPsi(parameters.project, EsdmPomTarget(scalar))
            if (item is NavigationItem && !processor.process(item)) return
        }
    }

    /** Declaration name to the scalars declaring it, across the whole project. */
    private fun declarations(project: Project): Map<String, List<YAMLScalar>> {
        val base = project.baseDir() ?: return emptyMap()
        val manager = PsiManager.getInstance(project)

        val result = mutableMapOf<String, MutableList<YAMLScalar>>()
        EsdmModelRoot.documentsUnder(base).forEach { file ->
            (manager.findFile(file) as? YAMLFile)?.documents?.forEach { document ->
                document.declaredName()?.let { scalar ->
                    result.getOrPut(scalar.textValue) { mutableListOf() } += scalar
                }
            }
        }
        return result
    }

    /**
     * `guessProjectDir` rather than resolving `basePath` through
     * `LocalFileSystem`: the project directory may not be on the local file
     * system at all — a test fixture's is in memory — and this asks the VFS that
     * actually owns it.
     */
    private fun Project.baseDir(): com.intellij.openapi.vfs.VirtualFile? {
        // Content roots first: that is the authoritative answer when the project
        // has a module. A project opened as a bare folder has none, so fall back
        // to the project file's own directory — which lives in whatever VFS owns
        // the project, in-memory ones included.
        com.intellij.openapi.roots.ProjectRootManager.getInstance(this).contentRoots.firstOrNull()
            ?.let { return it }
        return workspaceFile?.parent?.parent ?: projectFile?.parent?.parent
    }

    private fun YAMLDocument.declaredName(): YAMLScalar? {
        val mapping = topLevelValue as? YAMLMapping ?: return null
        // Only a document that declares a kind is an ESDM artifact.
        mapping.textOf("kind") ?: return null
        return (mapping.getKeyValueByKey("name")?.value as? YAMLScalar)
            ?.takeIf { it.textValue.isNotEmpty() }
    }
}
