package net.edditech.esdm.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import net.edditech.esdm.model.EsdmModelRoot
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * One pass over a model, kept until the model changes.
 *
 * The scan that makes resolution work without an index was re-reading every
 * document on every call — once per reference, per highlighting pass — and
 * doing it on the EDT, which the slow-operations assertion duly complained
 * about. Reading the model once and caching both directions costs the same
 * single pass and answers "what declares this?" and "what refers to this?"
 * equally well. The second question is what find-usages needs.
 *
 * Keyed on [PsiModificationTracker.MODIFICATION_COUNT], so an edit anywhere
 * invalidates it. That is coarse, and affordable: the pass is one parse of a
 * few dozen small files that the IDE has already parsed.
 */
object EsdmModelCache {

    private val DECLARATIONS =
        Key.create<CachedValue<Map<String, List<YAMLScalar>>>>("esdm.declarations")
    private val REFERENCES =
        Key.create<CachedValue<Map<String, List<YAMLScalar>>>>("esdm.references")

    /** Declaration `name:` scalars by key, for the model containing [origin]. */
    fun declarations(project: Project, origin: VirtualFile): Map<String, List<YAMLScalar>> =
        cached(project, origin, DECLARATIONS) { document, into ->
            document.declarationKeys()?.let { (keys, scalar) ->
                keys.forEach { key -> into.getOrPut(key) { mutableListOf() } += scalar }
            }
        }

    /** Referring scalars by the key they refer to, for the model containing [origin]. */
    fun references(project: Project, origin: VirtualFile): Map<String, List<YAMLScalar>> =
        cached(project, origin, REFERENCES) { document, into ->
            PsiTreeUtil.findChildrenOfType(document, YAMLScalar::class.java)
                .forEach { scalar ->
                    EsdmReferences.keyFor(scalar)?.let { key ->
                        into.getOrPut(key) { mutableListOf() } += scalar
                    }
                }
        }

    private fun cached(
        project: Project,
        origin: VirtualFile,
        key: Key<CachedValue<Map<String, List<YAMLScalar>>>>,
        collect: (YAMLDocument, MutableMap<String, MutableList<YAMLScalar>>) -> Unit,
    ): Map<String, List<YAMLScalar>> {
        val root = EsdmModelRoot.of(origin) ?: return emptyMap()
        val manager = PsiManager.getInstance(project)
        val directory = manager.findDirectory(root) ?: return emptyMap()

        return CachedValuesManager.getManager(project).getCachedValue(directory, key, {
            val collected = mutableMapOf<String, MutableList<YAMLScalar>>()
            EsdmModelRoot.documentsUnder(root).forEach { file ->
                (manager.findFile(file) as? YAMLFile)?.documents?.forEach { document ->
                    collect(document, collected)
                }
            }
            CachedValueProvider.Result.create(
                collected as Map<String, List<YAMLScalar>>,
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }, false)
    }

    /** Every key under which this document's `name:` is declared. */
    private fun YAMLDocument.declarationKeys(): Pair<List<String>, YAMLScalar>? {
        val mapping = topLevelValue as? YAMLMapping ?: return null
        val kind = mapping.text("kind") ?: return null
        val nameScalar = mapping.getKeyValueByKey("name")?.value as? YAMLScalar ?: return null
        val name = nameScalar.textValue.takeIf { it.isNotEmpty() } ?: return null

        val scope = mapping.getKeyValueByKey("scope")?.value as? YAMLMapping
        val keys = EsdmKeys.forDeclaration(
            kind,
            name,
            EsdmScope(
                domain = scope?.text("domain"),
                boundedContext = scope?.text("boundedContext"),
                aggregate = scope?.text("aggregate"),
                dynamicConsistencyBoundary = scope?.text("dynamicConsistencyBoundary"),
            ),
        )
        return if (keys.isEmpty()) null else keys to nameScalar
    }

    private fun YAMLMapping.text(key: String): String? =
        (getKeyValueByKey(key)?.value as? YAMLScalar)?.textValue?.takeIf { it.isNotEmpty() }
}
