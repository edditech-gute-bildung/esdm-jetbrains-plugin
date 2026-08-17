package net.edditech.esdm.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import net.edditech.esdm.lint.EsdmCli
import java.io.File

/**
 * Settings under Tools. Kotlin UI DSL v2 — v1 was removed in 2026.1, so any
 * older sample is a dead end.
 */
class EsdmConfigurable(private val project: Project) :
    BoundSearchableConfigurable("ESDM", "net.edditech.esdm.settings") {

    private val state get() = EsdmSettings.getInstance(project).state

    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox("Show esdm lint findings in the editor")
                .bindSelected(state::lintEnabled)
        }

        group("Command-line tool") {
            row("esdm binary:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile()
                        .withTitle("Select the esdm Binary"),
                    project,
                )
                    .bindText({ state.esdmPath.orEmpty() }, { state.esdmPath = it.ifBlank { null } })
                    .comment(discoveryComment())
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
        }

        group("Model") {
            row("Model root:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleDir()
                        .withTitle("Select the ESDM Model Root"),
                    project,
                )
                    .bindText({ state.modelRoot.orEmpty() }, { state.modelRoot = it.ifBlank { null } })
                    .comment(
                        "The directory <code>esdm lint</code> runs in. Left empty, it is inferred from the " +
                            "nearest <code>schemas/</code> directory, or the highest folder still holding " +
                            "<code>*.esdm.yaml</code> files. Set it explicitly when a model's root holds only " +
                            "sub-directories.",
                    )
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
        }
    }

    /**
     * Says what was actually found, rather than explaining the search order in
     * the abstract — the common question here is "which binary is it using?".
     */
    private fun discoveryComment(): String {
        val found: File? = EsdmCli.discover(project)
        return if (found == null) {
            "Not found. Leave empty to look for <code>./esdm</code> in the project, then on <code>PATH</code>. " +
                "Downloads: <a href='https://www.esdm.io/getting-started/installing-esdm/'>esdm.io</a>"
        } else {
            "Leave empty to discover automatically. Currently using <code>${found.absolutePath}</code>"
        }
    }
}
