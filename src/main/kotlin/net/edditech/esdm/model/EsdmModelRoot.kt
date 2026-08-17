package net.edditech.esdm.model

import com.intellij.openapi.vfs.VirtualFile
import net.edditech.esdm.schema.ESDM_FILE_SUFFIX

/**
 * Locating the directory that holds a model.
 *
 * Shared, because both linting and reference resolution need it and they must
 * agree: the linter runs from this directory, and a reference that cannot be
 * found in the index is looked for by scanning it.
 */
object EsdmModelRoot {

    /** Guards against walking to the filesystem root on a pathological tree. */
    private const val MAX_WALK_UP = 32

    /**
     * A `schemas/` directory is the strongest signal — it is exactly what
     * `esdm add-schema` writes at a project root. Failing that, the highest
     * ancestor that still holds `*.esdm.yaml` files directly.
     *
     * The second rule gets a model wrong when its root holds only
     * sub-directories and no documents of its own, which is why the lint
     * settings allow overriding it.
     */
    fun of(file: VirtualFile): VirtualFile? {
        var directory: VirtualFile? = if (file.isDirectory) file else file.parent ?: return null
        var best: VirtualFile? = null

        var remaining = MAX_WALK_UP
        while (directory != null && remaining-- > 0) {
            if (directory.findChild("schemas")?.isDirectory == true) return directory
            if (directory.children?.any { it.name.endsWith(ESDM_FILE_SUFFIX) } != true) break

            best = directory
            directory = directory.parent
        }
        return best ?: file.parent
    }

    /** Every `*.esdm.yaml` under [root], including nested bounded-context directories. */
    fun documentsUnder(root: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        fun walk(directory: VirtualFile, depth: Int) {
            if (depth > MAX_WALK_UP) return
            directory.children?.forEach { child ->
                when {
                    child.isDirectory -> walk(child, depth + 1)
                    child.name.endsWith(ESDM_FILE_SUFFIX) -> result += child
                }
            }
        }
        walk(root, 0)
        return result
    }
}
