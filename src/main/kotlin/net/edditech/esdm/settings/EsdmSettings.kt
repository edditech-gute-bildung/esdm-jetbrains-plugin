package net.edditech.esdm.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Per-project because the `esdm` binary is pinned per project: the linter
 * rejects local schemas that drift from the revision embedded in the binary, so
 * two projects on different ESDM versions need different executables.
 */
@Service(Service.Level.PROJECT)
@State(name = "EsdmSettings", storages = [Storage("esdm.xml")])
class EsdmSettings : SimplePersistentStateComponent<EsdmSettings.State>(State()) {

    class State : BaseState() {
        /** Explicit path to the `esdm` binary; empty means "discover it". */
        var esdmPath by string(null)

        /** Explicit model root; empty means "infer from the file being linted". */
        var modelRoot by string(null)

        var lintEnabled by property(true)
    }

    companion object {
        fun getInstance(project: Project): EsdmSettings = project.getService(EsdmSettings::class.java)
    }
}
