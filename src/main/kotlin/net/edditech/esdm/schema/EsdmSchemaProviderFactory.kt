package net.edditech.esdm.schema

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion

/** Suffix published by the core schema itself as `x-esdm-file-suffix`. */
const val ESDM_FILE_SUFFIX = ".esdm.yaml"

/**
 * Binds the bundled ESDM schema to `*.esdm.yaml`, which is what removes the
 * per-document `# yaml-language-server: $schema=...` modeline ESDM's docs
 * currently ask users to hand-write — one per document, with a hand-counted
 * relative path.
 */
class EsdmSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> =
        listOf(EsdmSchemaFileProvider())
}

class EsdmSchemaFileProvider : JsonSchemaFileProvider {

    override fun isAvailable(file: VirtualFile): Boolean = file.name.endsWith(ESDM_FILE_SUFFIX)

    override fun getName(): String = "ESDM"

    override fun getSchemaFile(): VirtualFile? =
        JsonSchemaProviderFactory.getResourceFile(EsdmSchemaFileProvider::class.java, SCHEMA_RESOURCE)

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema

    /**
     * The interface default is draft-4. ESDM is draft 2020-12 and leans on
     * `if`/`then`, `oneOf` and `unevaluatedProperties`, so leaving the default
     * in place would mis-parse the schema silently rather than loudly.
     */
    override fun getSchemaVersion(): JsonSchemaVersion = JsonSchemaVersion.SCHEMA_2020_12

    override fun getRemoteSource(): String = "https://schema.esdm.io/core/v1"

    private companion object {
        const val SCHEMA_RESOURCE = "/schemas/esdm.schema.json"
    }
}
