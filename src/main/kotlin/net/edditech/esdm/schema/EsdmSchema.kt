package net.edditech.esdm.schema

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Read access to the bundled merged schema, with just enough JSON Schema
 * evaluation to answer "what belongs here?" — the question the platform's own
 * engine answers incorrectly (see EsdmSchemaTest).
 *
 * Everything is derived from the schema. No kind, property or enum value is
 * written down in Kotlin, so a schema bump changes behaviour without changing
 * code.
 */
object EsdmSchema {

    private const val RESOURCE = "/schemas/esdm.schema.json"

    /** Field the merged schema's top-level `oneOf` is discriminated on. */
    const val API_VERSION = "apiVersion"

    /** Field the core schema's `if`/`then` blocks are discriminated on. */
    const val KIND = "kind"

    private val root: JsonObject by lazy {
        val stream = checkNotNull(EsdmSchema::class.java.getResourceAsStream(RESOURCE)) {
            "Bundled ESDM schema is missing from the plugin jar: $RESOURCE"
        }
        stream.use { JsonParser.parseReader(it.reader()).asJsonObject }
    }

    /**
     * `apiVersion` value to the branch describing it.
     *
     * The merged document dispatches with `allOf` of `if`/`then` on
     * `apiVersion`; the discriminator lives on the `if`, and the shape on the
     * matching `then`.
     */
    private val branches: Map<String, JsonObject> by lazy {
        root.getAsJsonArray("allOf").orEmpty()
            .mapNotNull { it.asJsonObjectOrNull() }
            .mapNotNull { entry ->
                val version = entry.getAsJsonObject("if")
                    ?.getAsJsonObject("properties")
                    ?.getAsJsonObject(API_VERSION)
                    ?.get("const")?.asStringOrNull()
                val branch = entry.getAsJsonObject("then")
                if (version != null && branch != null) version to branch else null
            }
            .toMap()
    }

    val apiVersions: Set<String> get() = branches.keys

    /**
     * Properties valid at [path] inside a document, given what the document
     * already says. Passing the data in is what makes discrimination work: the
     * `kind` decides which `if`/`then` applies, and a `context-mapping`'s `type`
     * decides which `oneOf` variant does.
     */
    fun propertiesAt(document: Map<String, Any?>, path: List<String>): List<Property> {
        val schemas = effectiveSchemas(document, path) ?: return emptyList()
        val required = schemas.flatMap { schema ->
            schema.getAsJsonArray("required").orEmpty().mapNotNull { it.asStringOrNull() }
        }.toSet()

        return schemas
            .mapNotNull { it.getAsJsonObject("properties") }
            .flatMap { it.entrySet() }
            .groupBy({ it.key }, { it.value })
            .map { (name, definitions) ->
                Property(
                    name = name,
                    description = definitions.firstNotNullOfOrNull { it.asJsonObjectOrNull()?.description() },
                    required = name in required,
                    type = definitions.firstNotNullOfOrNull { it.asJsonObjectOrNull()?.typeName() },
                )
            }
            .sortedBy { it.name }
    }

    /**
     * Allowed scalar values at [path]. Covers both `enum` and the `const` that
     * `oneOf` variants use as their discriminator — collecting the latter across
     * variants is what turns a `context-mapping`'s `type` into the eight DDD
     * patterns rather than the actor enum the platform offers.
     */
    fun valuesAt(document: Map<String, Any?>, path: List<String>): List<Value> {
        val schemas = effectiveSchemas(document, path) ?: return emptyList()

        return schemas.flatMap { schema ->
            val fromEnum = schema.getAsJsonArray("enum").orEmpty().mapNotNull { it.asStringOrNull() }
            val fromConst = listOfNotNull(schema.get("const")?.asStringOrNull())
            (fromEnum + fromConst).map { Value(it, schema.description()) }
        }.distinctBy { it.value }.sortedBy { it.value }
    }

    /**
     * The set of subschemas that apply at [path], with `$ref`, `allOf`,
     * `if`/`then` and `oneOf` resolved against [document].
     *
     * Returns null when the document's `apiVersion` is unknown — the caller
     * should then stay out of the way rather than guess.
     */
    private fun effectiveSchemas(document: Map<String, Any?>, path: List<String>): List<JsonObject>? {
        val apiVersion = document[API_VERSION] as? String ?: return null
        val branch = branches[apiVersion] ?: return null

        var current = expand(branch, document)
        var data: Any? = document

        for (segment in path) {
            @Suppress("UNCHECKED_CAST")
            val scope = data as? Map<String, Any?>
            val next = current.mapNotNull { schema ->
                schema.getAsJsonObject("properties")?.getAsJsonObject(segment)
            }
            if (next.isEmpty()) return emptyList()

            data = scope?.get(segment)
            // A sequence's schema describes its items; step through so a path
            // into a list element resolves rather than dead-ending.
            current = next.flatMap { schema ->
                val items = schema.getAsJsonObject("items")
                val target = if (data is List<*> && items != null) items else schema
                expand(target, data)
            }
        }
        return current
    }

    /**
     * Flattens one schema object into every subschema that is active for [data],
     * following `$ref`, merging `allOf`, evaluating `if`/`then`/`else` and
     * narrowing `oneOf` to the matching variant.
     *
     * When `oneOf` cannot be narrowed — typically because the discriminator has
     * not been typed yet — every variant is kept. That is deliberate: offering
     * the union of *plausible* options is honest, whereas the platform's
     * behaviour of silently picking one arbitrary branch is not.
     */
    private fun expand(schema: JsonObject, data: Any?, depth: Int = 0): List<JsonObject> {
        if (depth > MAX_DEPTH) return listOf(schema)

        val resolved = dereference(schema) ?: return emptyList()
        val result = mutableListOf(resolved)

        resolved.getAsJsonArray("allOf").orEmpty().mapNotNull { it.asJsonObjectOrNull() }.forEach { entry ->
            val conditional = entry.getAsJsonObject("if")
            if (conditional == null) {
                result += expand(entry, data, depth + 1)
            } else {
                val taken = if (matches(conditional, data)) entry.getAsJsonObject("then") else entry.getAsJsonObject("else")
                taken?.let { result += expand(it, data, depth + 1) }
            }
        }

        val variants = resolved.getAsJsonArray("oneOf").orEmpty().mapNotNull { it.asJsonObjectOrNull() }
        if (variants.isNotEmpty()) {
            val matching = variants.filter { matches(it, data) }
            val chosen = if (matching.size == 1) matching else variants
            chosen.forEach { result += expand(it, data, depth + 1) }
        }

        resolved.getAsJsonArray("anyOf").orEmpty().mapNotNull { it.asJsonObjectOrNull() }.forEach {
            result += expand(it, data, depth + 1)
        }

        return result
    }

    /**
     * Whether [data] is still *compatible* with [condition] — deliberately not
     * whether it is valid against it.
     *
     * The document being completed is half-written by definition, so validity is
     * the wrong test: every `context-mapping` variant lists its endpoints as
     * `required`, and judging by those would reject all eight variants before
     * the user has typed any endpoint, collapsing back to the union. Only an
     * explicitly contradicting value rules a branch out; a key not yet written
     * rules nothing out.
     */
    private fun matches(condition: JsonObject, data: Any?): Boolean {
        @Suppress("UNCHECKED_CAST")
        val map = data as? Map<String, Any?> ?: return false

        val properties = condition.getAsJsonObject("properties") ?: return true
        for ((key, definition) in properties.entrySet()) {
            val expected = definition.asJsonObjectOrNull() ?: continue
            val actual = map[key] ?: continue

            expected.get("const")?.asStringOrNull()?.let { if (actual != it) return false }
            expected.getAsJsonArray("enum")?.let { allowed ->
                if (allowed.mapNotNull { it.asStringOrNull() }.none { it == actual }) return false
            }
        }
        return true
    }

    private fun dereference(schema: JsonObject, depth: Int = 0): JsonObject? {
        if (depth > MAX_DEPTH) return schema
        val pointer = schema.get("\$ref")?.asStringOrNull() ?: return schema
        if (!pointer.startsWith("#/")) return null

        var current: JsonElement = root
        for (rawSegment in pointer.removePrefix("#/").split("/")) {
            val segment = rawSegment.replace("~1", "/").replace("~0", "~")
            val obj = current.asJsonObjectOrNull() ?: return null
            current = obj.get(segment) ?: return null
        }
        return current.asJsonObjectOrNull()?.let { dereference(it, depth + 1) }
    }

    data class Property(
        val name: String,
        val description: String?,
        val required: Boolean,
        val type: String?,
    )

    data class Value(val value: String, val description: String?)

    private const val MAX_DEPTH = 24
}

private fun JsonObject.description(): String? = get("description")?.asStringOrNull()

private fun JsonObject.typeName(): String? = get("type")?.asStringOrNull()

private fun JsonElement.asJsonObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

private fun JsonElement.asStringOrNull(): String? =
    takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun com.google.gson.JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
