package net.edditech.esdm.build

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag

/**
 * Turns the three ESDM schemas into the single JSON schema the plugin bundles.
 *
 * Two transformations, each solving a concrete problem:
 *
 * **Lift comments into `description`.** ESDM documents its schemas in YAML
 * comments rather than `description:` keys — `core/v1.yaml` carries 768 comment
 * lines against 4 descriptions. IntelliJ's hover documentation and completion
 * tail text read `description`, so a plain YAML-to-JSON conversion would throw
 * away the best documentation ESDM has. The convention is consistent enough to
 * exploit mechanically: a comment block sitting as the first thing inside a
 * mapping documents that mapping.
 *
 * Not every comment is documentation, though: a few explain the schema files'
 * own organisation rather than any modelled concept, and those are dropped
 * again — see [dropSchemaHousekeeping]. The rule catches the ones written more
 * than once; a single-instance note still slips through, and one does
 * (`domain-storytelling/$defs/scopeDomain`). Distinguishing those reliably
 * means reading intent, so the residue is accepted rather than guessed at.
 *
 * **Merge into one document dispatched on `apiVersion`.** A single merged
 * schema sidesteps per-file dispatch: `JsonSchemaFileProvider` matches per
 * file, while ESDM's unit is the document and one file may legitimately mix
 * `apiVersion`s. Dispatch is `allOf` of `if`/`then` rather than `oneOf`,
 * because `oneOf` made the engine report "Validates to more than one variant"
 * on every complete context-mapping.
 */
@CacheableTask
abstract class MergeEsdmSchemas : DefaultTask() {

    /** Directory in the `schemas/{name}/{version}.yaml` layout `esdm add-schema` writes. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemasDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun merge() {
        val defs = JsonObject()
        val branches = linkedMapOf<String, JsonObject>()
        var lifted = 0

        for ((slug, relativePath) in SOURCES) {
            val file = schemasDirectory.get().file(relativePath).asFile
            check(file.isFile) { "Missing ESDM schema: $file" }

            val counter = IntArray(1)
            val root = convert(compose(file.readText()), counter) as? JsonObject
                ?: error("Schema root must be a mapping: $file")
            lifted += counter[0]

            // $defs are hoisted into a per-source namespace so the three schemas
            // cannot collide; every internal pointer is rewritten to match.
            root.remove("\$defs")?.let { defs.add(slug, rewriteRefs(it, slug) as JsonObject) }

            // $id would open a new base URI scope on the branch and break the
            // rewritten pointers; $schema is only meaningful at the root.
            root.remove("\$id")
            root.remove("\$schema")
            root.keySet().filter { it.startsWith("x-esdm-") }.forEach { root.remove(it) }

            val branch = rewriteRefs(root, slug) as JsonObject
            val apiVersion = branch.getAsJsonObject("properties")
                ?.getAsJsonObject("apiVersion")
                ?.get("const")?.asString
                ?: error("Schema has no apiVersion const to dispatch on: $file")
            branches[apiVersion] = branch
        }

        // Dispatch with `if`/`then` on apiVersion rather than `oneOf`.
        //
        // `oneOf` was the first choice, because IntelliJ discriminates its
        // branches better than it does conditionals. Measured, it was wrong
        // twice over: completion did not discriminate at all (which is why the
        // plugin ships its own contributor, so the schema no longer has to be
        // good at completion), and validation reported "Validates to more than
        // one variant" on every *complete* context-mapping while leaving
        // incomplete ones alone — a false positive on correct models, which is
        // the worst kind.
        //
        // `if`/`then` says exactly what is meant: this apiVersion implies this
        // shape. Nothing is claimed about mutual exclusivity, so there is no
        // "more than one variant" to get wrong.
        val dispatch = JsonArray()
        branches.forEach { (apiVersion, branch) ->
            dispatch.add(
                JsonObject().apply {
                    add(
                        "if",
                        JsonObject().apply {
                            add(
                                "properties",
                                JsonObject().apply {
                                    add("apiVersion", JsonObject().apply { addProperty("const", apiVersion) })
                                },
                            )
                            add("required", JsonArray().apply { add("apiVersion") })
                        },
                    )
                    add("then", branch)
                },
            )
        }

        val merged = JsonObject().apply {
            addProperty("\$schema", "https://json-schema.org/draft/2020-12/schema")
            addProperty("\$id", "https://schema.esdm.io/merged/v1")
            addProperty("title", "ESDM")
            addProperty(
                "description",
                "Merged ESDM schema: core plus extensions, dispatched on apiVersion. " +
                    "Generated from the upstream schemas — edit those, not this file.",
            )
            // Keeps a typo in apiVersion itself an error; without it an unknown
            // value would simply match no branch and be silently unvalidated.
            add(
                "properties",
                JsonObject().apply {
                    add(
                        "apiVersion",
                        JsonObject().apply {
                            add("enum", JsonArray().apply { branches.keys.forEach { add(it) } })
                        },
                    )
                },
            )
            // The sources are MIT-licensed and their notice must not be removed;
            // this file is a derived work, so it carries the notice too.
            addProperty(
                "\$comment",
                "Derived from the ESDM schemas, Copyright (c) 2026 the native web GmbH, " +
                    "MIT licensed. Full terms: https://www.esdm.io/introduction/license/",
            )
            add("\$defs", defs)
            add("allOf", dispatch)
        }

        val dropped = dropSchemaHousekeeping(merged)

        val broken = brokenRefs(merged)
        check(broken.isEmpty()) { "Merged schema has unresolvable pointers: $broken" }

        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(GsonBuilder().setPrettyPrinting().create().toJson(merged) + "\n")

        logger.lifecycle(
            "Merged ${SOURCES.size} ESDM schemas into ${output.name}: " +
                "$lifted descriptions lifted from comments, $dropped dropped as schema housekeeping, " +
                "${output.length() / 1024} KB",
        )
    }

    private fun compose(yaml: String): Node {
        val options = LoaderOptions().apply { isProcessComments = true }
        return Yaml(options).compose(yaml.reader())
            ?: error("Schema is empty")
    }

    /**
     * Converts a SnakeYAML node tree to JSON, attaching lifted comments as
     * `description` on the way.
     *
     * A block comment attaches to the node that follows it, so a comment written
     * as the first thing inside a mapping arrives on that mapping's *first key*.
     * That is exactly ESDM's documentation style, so the first key's block
     * comment becomes the enclosing mapping's `description`.
     */
    private fun convert(node: Node, lifted: IntArray, parentKey: String? = null): Any = when (node) {
        is MappingNode -> {
            val json = JsonObject()
            for (tuple in node.value) {
                val key = (tuple.keyNode as ScalarNode).value
                json.add(key, toElement(convert(tuple.valueNode, lifted, key)))
            }

            val firstKey = node.value.firstOrNull()?.keyNode
            val comment = firstKey?.blockComments
                ?.mapNotNull { it.value?.removePrefix(" ")?.trimEnd() }
                ?.let(::reflow)

            // Never overwrite a real `description:`, and never inject one into a
            // container of schemas: the mapping under `properties` is keyed by
            // user-chosen property names, so a `description` there would invent
            // a property literally called "description".
            if (!comment.isNullOrBlank() && !json.has("description") && parentKey !in CONTAINER_KEYS) {
                json.addProperty("description", comment)
                lifted[0]++
            }
            json
        }

        is SequenceNode -> JsonArray().apply {
            // Items keep the sequence's own key as parent, so `properties` never
            // leaks through an array into an element mapping.
            node.value.forEach { add(toElement(convert(it, lifted, parentKey))) }
        }

        is ScalarNode -> scalar(node)

        else -> error("Unsupported node: $node")
    }

    /**
     * SnakeYAML resolves more spellings than the obvious ones, and the naive
     * conversions are wrong in different ways: `String.toBoolean()` is only
     * `equals("true")`, so YAML 1.1's `yes`/`on`/`y` would silently become
     * `false` and invert a schema keyword; `"0x1F".toLong()` and `".inf"
     * .toDouble()` throw. Neither form appears in the schemas today, but the
     * refresh workflow (`./esdm add-schema`) can introduce them at any time,
     * and a silent inversion is the kind of bug nobody goes looking for.
     */
    private fun scalar(node: ScalarNode): Any = when (node.tag) {
        Tag.NULL -> JsonNull.INSTANCE
        Tag.BOOL -> JsonPrimitive(toBoolean(node))
        Tag.INT -> JsonPrimitive(toLong(node))
        Tag.FLOAT -> JsonPrimitive(toDouble(node))
        else -> JsonPrimitive(node.value)
    }

    private fun toBoolean(node: ScalarNode): Boolean = when (node.value.lowercase()) {
        in YAML_TRUE -> true
        in YAML_FALSE -> false
        else -> fail(node, "not a YAML boolean")
    }

    private fun toLong(node: ScalarNode): Long {
        val raw = node.value.replace("_", "")
        val negative = raw.startsWith("-")
        val body = raw.removePrefix("+").removePrefix("-")
        val value = when {
            body.startsWith("0x", ignoreCase = true) -> body.drop(2).toLongOrNull(16)
            body.startsWith("0o", ignoreCase = true) -> body.drop(2).toLongOrNull(8)
            body.startsWith("0b", ignoreCase = true) -> body.drop(2).toLongOrNull(2)
            else -> body.toLongOrNull()
        } ?: fail(node, "not a YAML integer")
        return if (negative) -value else value
    }

    private fun toDouble(node: ScalarNode): Double {
        val raw = node.value.replace("_", "")
        return when (raw.lowercase()) {
            ".inf", "+.inf" -> Double.POSITIVE_INFINITY
            "-.inf" -> Double.NEGATIVE_INFINITY
            ".nan" -> Double.NaN
            else -> raw.toDoubleOrNull() ?: fail(node, "not a YAML float")
        }
    }

    private fun fail(node: ScalarNode, why: String): Nothing {
        val mark = node.startMark
        error("${node.value.take(40)}: $why (line ${mark.line + 1}, column ${mark.column + 1})")
    }

    private fun toElement(value: Any) = when (value) {
        is JsonObject, is JsonArray, is JsonPrimitive, is JsonNull -> value as com.google.gson.JsonElement
        else -> error("Unexpected converted value: $value")
    }

    /** Joins comment lines into paragraphs; a blank comment line separates them. */
    private fun reflow(lines: List<String>): String =
        lines.joinToString("\n")
            .split(Regex("\n\\s*\n"))
            .map { paragraph -> paragraph.lines().joinToString(" ") { it.trim() }.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

    private fun rewriteRefs(element: com.google.gson.JsonElement, slug: String): com.google.gson.JsonElement =
        when {
            element.isJsonObject -> JsonObject().also { out ->
                for ((key, value) in element.asJsonObject.entrySet()) {
                    val rewritten = if (key == "\$ref" && value.isJsonPrimitive &&
                        value.asString.startsWith(LOCAL_DEFS)
                    ) {
                        JsonPrimitive(value.asString.replaceFirst(LOCAL_DEFS, "$LOCAL_DEFS$slug/"))
                    } else {
                        rewriteRefs(value, slug)
                    }
                    out.add(key, rewritten)
                }
            }

            element.isJsonArray -> JsonArray().also { out ->
                element.asJsonArray.forEach { out.add(rewriteRefs(it, slug)) }
            }

            else -> element
        }

    /**
     * Removes lifted descriptions that turn out to be about the schema rather
     * than the model, and returns how many went.
     *
     * Not every comment in the sources documents a concept. Some explain the
     * files' own organisation — "Inlined rather than `$ref`-ed into the core so
     * the extension document validates in isolation" sits on three unrelated
     * definitions — and lifting those puts a note for schema maintainers in
     * front of someone hovering a field in their model.
     *
     * The signal is the repetition. A description of a concept is written once,
     * for that concept; a note about file layout gets pasted wherever the layout
     * repeats. So a long description appearing verbatim in more than one place
     * is housekeeping, and is dropped.
     */
    private fun dropSchemaHousekeeping(root: JsonObject): Int {
        val counts = mutableMapOf<String, Int>()
        fun count(element: com.google.gson.JsonElement) {
            when {
                element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                    if (key == "description" && value.isJsonPrimitive) {
                        counts.merge(value.asString, 1, Int::plus)
                    } else {
                        count(value)
                    }
                }

                element.isJsonArray -> element.asJsonArray.forEach(::count)
            }
        }
        count(root)

        val housekeeping = counts.filterValues { it > 1 }.keys.filter { it.length >= MIN_UNIQUE_DESCRIPTION }
        if (housekeeping.isEmpty()) return 0

        var removed = 0
        fun prune(element: com.google.gson.JsonElement) {
            when {
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    val description = obj.get("description")
                    if (description != null && description.isJsonPrimitive && description.asString in housekeeping) {
                        obj.remove("description")
                        removed++
                    }
                    obj.entrySet().toList().forEach { (_, value) -> prune(value) }
                }

                element.isJsonArray -> element.asJsonArray.forEach(::prune)
            }
        }
        prune(root)
        return removed
    }

    private fun brokenRefs(root: JsonObject): List<String> {
        val refs = mutableSetOf<String>()
        fun collect(element: com.google.gson.JsonElement) {
            when {
                element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                    if (key == "\$ref" && value.isJsonPrimitive) refs += value.asString else collect(value)
                }

                element.isJsonArray -> element.asJsonArray.forEach(::collect)
            }
        }
        collect(root)

        return refs.filter { pointer ->
            // Merging strips `$id` and `$schema` from every branch, which is
            // exactly the base URI an absolute or relative ref would resolve
            // against. Anything not purely local is therefore dangling by
            // construction — the guard has to be loudest precisely where the
            // transform is most dangerous, not silent there.
            if (!pointer.startsWith("#/")) return@filter true

            var current: com.google.gson.JsonElement? = root
            for (rawSegment in pointer.removePrefix("#/").split("/")) {
                val segment = rawSegment.replace("~1", "/").replace("~0", "~")
                current = when {
                    current?.isJsonObject == true ->
                        current.asJsonObject.takeIf { it.has(segment) }?.get(segment)

                    // JSON Pointer addresses array elements by index, e.g.
                    // `#/allOf/2/$defs/x`. Treating a non-object as broken
                    // would reject valid pointers.
                    current?.isJsonArray == true ->
                        segment.toIntOrNull()
                            ?.let { current.asJsonArray.takeIf { arr -> it in 0 until arr.size() }?.get(it) }

                    else -> null
                } ?: return@filter true
            }
            false
        }
    }

    private companion object {
        val SOURCES = linkedMapOf(
            "core" to "core/v1.yaml",
            "given-when-then" to "given-when-then/v1.yaml",
            "domain-storytelling" to "domain-storytelling/v1.yaml",
        )

        val CONTAINER_KEYS = setOf(
            "properties", "\$defs", "definitions", "patternProperties", "dependentSchemas",
        )

        /** YAML 1.1 boolean spellings, as SnakeYAML's resolver tags them. */
        val YAML_TRUE = setOf("true", "yes", "on", "y")
        val YAML_FALSE = setOf("false", "no", "off", "n")

        const val LOCAL_DEFS = "#/\$defs/"

        /** Below this length, a repeated description is more likely coincidence than housekeeping. */
        const val MIN_UNIQUE_DESCRIPTION = 40
    }
}
