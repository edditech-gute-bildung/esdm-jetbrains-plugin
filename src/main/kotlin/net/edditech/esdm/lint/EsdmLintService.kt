package net.edditech.esdm.lint

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiModificationTracker
import net.edditech.esdm.schema.ESDM_FILE_SUFFIX
import net.edditech.esdm.settings.EsdmSettings

/**
 * Runs `esdm lint` once per model, not once per file.
 *
 * The CLI resolves references across the whole model directory, so per-file
 * linting is not merely wasteful — it is wrong, reporting
 * `unresolved-reference` for every document the file legitimately points at.
 * Highlighting, however, arrives file by file. This service bridges the two:
 * the first file to ask triggers a whole-model run, and every other file in the
 * same pass reads the cached result.
 */
@Service(Service.Level.PROJECT)
class EsdmLintService(private val project: Project) {

    private data class Key(val root: String, val stamp: Long)

    private val lock = Any()
    private var cachedKey: Key? = null
    private var cachedResult: EsdmCli.Outcome = EsdmCli.Outcome.Findings(emptyList())

    /**
     * Findings for [file], or null when linting is off or the model root cannot
     * be established. Note the CLI reads from disk, so edits that have not been
     * saved yet are not reflected; the IDE's autosave usually makes that
     * invisible, but it is why a finding can lag a keystroke.
     */
    fun findingsFor(file: VirtualFile): Result? {
        val settings = EsdmSettings.getInstance(project)
        if (!settings.state.lintEnabled) return null

        val root = modelRootFor(file) ?: return null
        val executable = EsdmCli.discover(project) ?: return Result(EsdmCli.Outcome.NotInstalled, emptyList())

        val key = Key(root.path, PsiModificationTracker.getInstance(project).modificationCount)
        val outcome = synchronized(lock) {
            if (cachedKey != key) {
                cachedResult = EsdmCli.lint(executable, root)
                cachedKey = key
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

        var directory: VirtualFile? = file.parent ?: return null
        var best: VirtualFile? = null

        // Walks up while the folders still hold model documents, and stops at the
        // first that does not. Anchoring the walk on the project base path
        // instead looked reasonable and was wrong: content roots need not sit
        // under it, and the walk then stopped one level too low.
        var remaining = MAX_WALK_UP
        while (directory != null && remaining-- > 0) {
            if (directory.findChild("schemas")?.isDirectory == true) return directory
            val holdsDocuments = directory.children?.any { it.name.endsWith(ESDM_FILE_SUFFIX) } == true
            if (!holdsDocuments) break

            best = directory
            directory = directory.parent
        }
        return best ?: file.parent
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
