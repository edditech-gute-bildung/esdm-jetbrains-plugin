package net.edditech.esdm.index

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.IntInlineKeyDescriptor
import com.intellij.util.io.KeyDescriptor
import net.edditech.esdm.schema.ESDM_FILE_SUFFIX
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

/**
 * Maps every ESDM declaration to where it is declared, so a reference can be
 * resolved without reading the whole model.
 *
 * A file-based index rather than a stub index — not a preference, a constraint.
 * Stub indexing needs `IStubElementType` constants baked into the language's own
 * parser and a `StubBasedPsiElement` hierarchy, both owned by whoever defines
 * the language. The YAML plugin defines no stub types, so this is the available
 * mechanism.
 */
class EsdmDeclarationIndex : FileBasedIndexExtension<String, Int>() {

    override fun getName(): ID<String, Int> = KEY

    override fun getVersion(): Int = 1

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer() = IntInlineKeyDescriptor()

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        object : DefaultFileTypeSpecificInputFilter(YAMLFileType.YML) {
            override fun acceptInput(file: com.intellij.openapi.vfs.VirtualFile): Boolean =
                file.name.endsWith(ESDM_FILE_SUFFIX)
        }

    /**
     * Indexing may only look at the content it is handed — never at other files,
     * settings, or the CLI — because the result is cached against this file
     * alone. Every key is therefore derived from one document's own `scope`.
     */
    override fun getIndexer(): DataIndexer<String, Int, FileContent> = DataIndexer { content ->
        val file = content.psiFile as? YAMLFile ?: return@DataIndexer emptyMap()

        buildMap {
            file.documents.forEach { document ->
                val mapping = document.topLevelValue as? YAMLMapping ?: return@forEach
                val kind = mapping.text("kind") ?: return@forEach
                val nameValue = mapping.getKeyValueByKey("name")?.value as? YAMLScalar ?: return@forEach
                val name = nameValue.textValue.takeIf { it.isNotEmpty() } ?: return@forEach

                val scope = mapping.getKeyValueByKey("scope")?.value as? YAMLMapping
                val declaredIn = EsdmScope(
                    domain = scope?.text("domain"),
                    boundedContext = scope?.text("boundedContext"),
                    aggregate = scope?.text("aggregate"),
                    dynamicConsistencyBoundary = scope?.text("dynamicConsistencyBoundary"),
                )

                EsdmKeys.forDeclaration(kind, name, declaredIn).forEach { key ->
                    put(key, nameValue.textRange.startOffset)
                }
            }
        }
    }

    private fun YAMLMapping.text(key: String): String? =
        (getKeyValueByKey(key)?.value as? YAMLScalar)?.textValue?.takeIf { it.isNotEmpty() }

    companion object {
        val KEY: ID<String, Int> = ID.create("net.edditech.esdm.declarations")
    }
}

/**
 * The single place both sides of a reference agree on a spelling.
 *
 * Declarations and references are written differently in ESDM — an event
 * declares a full `scope`, while something referring to it writes
 * `{boundedContext, aggregate, event}` and omits the domain — so the keys have
 * to be built from the schema's reference shapes rather than from the
 * declaration alone. Keeping both constructions in one file is what stops them
 * drifting apart.
 */
object EsdmKeys {

    /** Aggregate-owned and free-standing events must never collide, so the slot stays. */
    private const val NO_OWNER = ""

    fun forDeclaration(kind: String, name: String, scope: EsdmScope): List<String> = when (kind) {
        "domain" -> listOf(domain(name))

        // Referenced with a domain by `context-mapping`, without one by
        // `subdomain.boundedContexts`, so both spellings are indexed.
        "bounded-context" -> listOfNotNull(
            scope.domain?.let { boundedContext(it, name) },
            boundedContextAnyDomain(name),
        )

        "aggregate", "dynamic-consistency-boundary" ->
            listOfNotNull(scope.boundedContext?.let { consistencyUnit(it, name) })

        // An event's scope carries its owner: `aggregate` when owned, absent
        // when free-standing. Keeping the slot is what stops a
        // `{boundedContext, event}` pair resolving to an aggregate-owned event.
        "event" -> listOfNotNull(
            scope.boundedContext?.let { event(it, scope.aggregate, name) },
        )

        "command" -> listOfNotNull(
            scope.boundedContext?.let { boundedContext ->
                val owner = scope.aggregate ?: scope.dynamicConsistencyBoundary ?: return@let null
                command(boundedContext, owner, name)
            },
        )

        else -> emptyList()
    }

    fun domain(name: String) = "domain|$name"

    fun boundedContext(domain: String, name: String) = "bounded-context|$domain|$name"

    fun boundedContextAnyDomain(name: String) = "bounded-context|*|$name"

    fun consistencyUnit(boundedContext: String, name: String) = "unit|$boundedContext|$name"

    /**
     * @param owner the aggregate that owns the event, or null for a free-standing
     *   event emitted by a boundary-bound command. The two are distinct
     *   references and must not resolve to one another.
     */
    fun event(boundedContext: String, owner: String?, name: String) =
        "event|$boundedContext|${owner ?: NO_OWNER}|$name"

    fun command(boundedContext: String, owner: String, name: String) =
        "command|$boundedContext|$owner|$name"
}

/** The addressing part of a document's `scope`, as the schema defines it. */
data class EsdmScope(
    val domain: String? = null,
    val boundedContext: String? = null,
    val aggregate: String? = null,
    val dynamicConsistencyBoundary: String? = null,
)
