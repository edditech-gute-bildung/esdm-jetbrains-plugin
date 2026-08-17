package net.edditech.esdm.lint

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import net.edditech.esdm.settings.EsdmSettings
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Runs `esdm lint` and turns its output into findings.
 *
 * The binary is deliberately not bundled: it is platform-specific, about 8 MB,
 * and pinned per project — the ESDM docs have people download the build that
 * matches their model's schema revision. Shipping one version inside the plugin
 * would fight that. JetBrains also ask plugin vendors not to bundle large
 * binaries.
 */
object EsdmCli {

    private val log = logger<EsdmCli>()

    private const val EXECUTABLE = "esdm"
    private const val TIMEOUT_MS = 30_000

    /** Outcome of a lint run. The three cases are genuinely different to the caller. */
    sealed interface Outcome {
        /** The CLI ran and reported. An empty list means a clean model, not a failure. */
        data class Findings(val findings: List<EsdmFinding>) : Outcome

        /** No binary found. Expected on a fresh checkout; must stay quiet, not throw. */
        data object NotInstalled : Outcome

        /** The CLI ran and something went wrong — bad directory, unreadable model. */
        data class Failed(val message: String) : Outcome
    }

    /**
     * Where the binary lives: an explicit setting wins, then a copy sitting next
     * to the model (what ESDM's own instructions produce), then `PATH`.
     */
    fun discover(project: Project): File? {
        EsdmSettings.getInstance(project).state.esdmPath
            ?.takeIf { it.isNotBlank() }
            ?.let { configured ->
                val file = File(configured)
                return file.takeIf { it.canExecute() }
            }

        project.basePath?.let { base ->
            val local = File(base, executableName())
            if (local.canExecute()) return local
        }

        return findOnPath()
    }

    private fun executableName(): String = if (SystemInfo.isWindows) "$EXECUTABLE.exe" else EXECUTABLE

    private fun findOnPath(): File? =
        System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.asSequence()
            ?.map { File(it, executableName()) }
            ?.firstOrNull { it.canExecute() }

    /**
     * Lints the whole model directory — the only way `esdm lint` is meaningful.
     * It resolves references across every document, so a single file linted in
     * isolation reports `unresolved-reference` for everything outside it.
     */
    fun lint(executable: File, modelRoot: File): Outcome {
        val commandLine = GeneralCommandLine(executable.absolutePath, "lint")
            .withParameters("--format", "json", "--color", "never")
            // `location.file` comes back relative to the directory resolved from
            // the process working directory, so both are pinned explicitly
            // rather than inherited.
            .withParameters("--directory", ".")
            .withWorkDirectory(modelRoot.absolutePath)
            .withCharset(StandardCharsets.UTF_8)

        val output = try {
            CapturingProcessHandler(commandLine).runProcess(TIMEOUT_MS)
        } catch (e: Exception) {
            log.warn("Failed to run $executable", e)
            return Outcome.Failed(e.message ?: "could not start ${executable.name}")
        }

        if (output.isTimeout) return Outcome.Failed("esdm lint timed out after ${TIMEOUT_MS / 1000}s")

        // Measured contract: exit 0 means no errors and exit 1 means either
        // "errors found" or "the tool broke". Only stdout tells those apart —
        // findings always arrive as a JSON array, a failure leaves stdout empty
        // and writes to stderr. Keying on the exit code alone would report tool
        // failures as an absence of problems.
        return parse(output.stdout)
            ?: Outcome.Failed(output.stderr.lineSequence().firstOrNull { it.isNotBlank() } ?: "esdm lint failed")
    }

    /** Returns null when stdout is not a findings array, which signals tool failure. */
    internal fun parse(stdout: String): Outcome.Findings? {
        if (stdout.isBlank()) return null
        val array = try {
            JsonParser.parseString(stdout).takeIf { it.isJsonArray }?.asJsonArray ?: return null
        } catch (e: Exception) {
            log.debug("esdm lint produced unparseable stdout", e)
            return null
        }

        return Outcome.Findings(
            array.mapNotNull { element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val location = obj.getAsJsonObject("location")
                EsdmFinding(
                    ruleId = obj.get("ruleId")?.asString ?: return@mapNotNull null,
                    severity = EsdmSeverity.of(obj.get("severity")?.asString),
                    message = obj.get("message")?.asString.orEmpty(),
                    file = location?.get("file")?.asString ?: return@mapNotNull null,
                    line = location.get("line")?.asInt ?: 1,
                    column = location.get("column")?.asInt ?: 1,
                )
            },
        )
    }
}

data class EsdmFinding(
    val ruleId: String,
    val severity: EsdmSeverity,
    val message: String,
    /** Relative to the model root the lint ran against. */
    val file: String,
    /** 1-based, as the CLI reports them. */
    val line: Int,
    val column: Int,
)

enum class EsdmSeverity {
    ERROR,
    WARNING,
    ;

    companion object {
        fun of(raw: String?): EsdmSeverity = if (raw.equals("error", ignoreCase = true)) ERROR else WARNING
    }
}
