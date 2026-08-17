package net.edditech.esdm.lint

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import net.edditech.esdm.model.EsdmModelRoot
import net.edditech.esdm.schema.ESDM_FILE_SUFFIX
import net.edditech.esdm.settings.EsdmSettings
import java.io.File

/**
 * Runs `esdm lint` once per model, against the text the editor is showing.
 *
 * Two problems solved together, because they had one cause. The CLI reads
 * files from disk, but a user edits a document in memory, and the gap between
 * the two is where both symptoms came from: findings only refreshed when the
 * IDE happened to autosave, which reads as "the linter is very slow"; and until
 * it did, the findings described the *previous* content, so an error could
 * persist after being fixed and vanish after being reintroduced.
 *
 * Linting a mirror of the model — same layout, current editor content — closes
 * that gap. Caching on a fingerprint of that content closes the other half: the
 * previous cache key was a PSI modification count, which changes on every
 * keystroke, so the process was re-spawned constantly to recompute an identical,
 * already-stale answer.
 */
@Service(Service.Level.PROJECT)
class EsdmLintService(private val project: Project) {

    private val lock = Any()
    private var cachedFingerprint: Int? = null
    private var cachedResult: EsdmCli.Outcome = EsdmCli.Outcome.Findings(emptyList())
    private var mirror: File? = null

    /** Test seam: counts actual CLI invocations, so caching can be asserted rather than assumed. */
    @Volatile
    internal var runCount: Int = 0
        private set

    fun findingsFor(file: VirtualFile): Result? {
        val settings = EsdmSettings.getInstance(project)
        if (!settings.state.lintEnabled) return null

        val root = modelRootFor(file) ?: return null
        val executable = EsdmCli.discover(project) ?: return Result(EsdmCli.Outcome.NotInstalled, emptyList())

        val content = collectContent(root)
        if (content.isEmpty()) return null

        val outcome = synchronized(lock) {
            val fingerprint = content.hashCode()
            if (cachedFingerprint != fingerprint) {
                cachedResult = lintMirror(executable, content)
                cachedFingerprint = fingerprint
            }
            cachedResult
        }

        val relative = relativePath(root, file) ?: return Result(outcome, emptyList())
        val mine = (outcome as? EsdmCli.Outcome.Findings)
            ?.findings
            ?.filter { it.file.normalisePath() == relative }
            .orEmpty()

        return Result(outcome, mine)
    }

    data class Result(val outcome: EsdmCli.Outcome, val findings: List<EsdmFinding>)

    /**
     * Relative path to current text for every document in the model, preferring
     * the in-memory version over what is on disk.
     *
     * Documents are read under a read action because this runs on the
     * annotator's background phase, which holds none.
     *
     * Plugin Verifier flags `ReadAction.compute` as deprecated on 262, and this
     * stays deliberately. Both blocking forms — this and the Kotlin
     * `runReadAction` — are deprecated in favour of the suspending
     * `readAction {}`, and the caller is `ExternalAnnotator.doAnnotate`, which is
     * not a suspend function. Restructuring the annotator around coroutines to
     * silence a warning would be worse code than the warning.
     */
    private fun collectContent(root: VirtualFile): Map<String, String> =
        ReadAction.compute<Map<String, String>, RuntimeException> {
            val documents = FileDocumentManager.getInstance()
            buildMap {
                collectFiles(root).forEach { virtualFile ->
                    val relative = relativePath(root, virtualFile) ?: return@forEach
                    val text = documents.getCachedDocument(virtualFile)?.text
                        ?: runCatching { String(virtualFile.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
                    if (text != null) put(relative, text)
                }
            }
        }

    /**
     * Model documents, plus `schemas/` when the project vendors it — the linter
     * rejects local schemas that drift from the binary's own revision, and
     * omitting them from the mirror would quietly suppress that check.
     */
    private fun collectFiles(root: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        fun walk(directory: VirtualFile, insideSchemas: Boolean) {
            directory.children?.forEach { child ->
                when {
                    child.isDirectory -> walk(child, insideSchemas || child.name == "schemas")
                    insideSchemas || child.name.endsWith(ESDM_FILE_SUFFIX) -> result += child
                }
            }
        }
        walk(root, false)
        return result
    }

    private fun lintMirror(executable: File, content: Map<String, String>): EsdmCli.Outcome {
        val directory = mirror ?: FileUtil.createTempDirectory("esdm-lint", project.locationHash, true)
            .also { mirror = it }

        return try {
            FileUtil.delete(directory)
            content.forEach { (relative, text) ->
                val target = File(directory, relative)
                target.parentFile?.mkdirs()
                target.writeText(text)
            }
            runCount++
            EsdmCli.lint(executable, directory)
        } catch (e: Exception) {
            EsdmCli.Outcome.Failed(e.message ?: "could not prepare the model for linting")
        }
    }

    /**
     * The directory to lint from.
     *
     * A `schemas/` directory is the strongest signal — it is exactly what
     * `esdm add-schema` writes at a project root. Failing that, the highest
     * ancestor that still holds `*.esdm.yaml` files directly. That second rule
     * gets a model wrong when its root holds only subdirectories and no
     * documents of its own, which is why the setting exists to override it.
     */
    fun modelRootFor(file: VirtualFile): VirtualFile? {
        EsdmSettings.getInstance(project).state.modelRoot
            ?.takeIf { it.isNotBlank() }
            ?.let { return LocalFileSystem.getInstance().findFileByPath(it) }

        return EsdmModelRoot.of(file)
    }

    private fun relativePath(root: VirtualFile, file: VirtualFile): String? =
        file.path.removePrefix(root.path).removePrefix("/").takeIf { it.isNotEmpty() }

    /** The CLI emits `./a/b.esdm.yaml` on some paths and `a/b.esdm.yaml` on others. */
    private fun String.normalisePath(): String = removePrefix("./")

    companion object {
        /** Guards against walking to the filesystem root on a pathological tree. */
        private const val MAX_WALK_UP = 32

        fun getInstance(project: Project): EsdmLintService = project.getService(EsdmLintService::class.java)
    }
}
