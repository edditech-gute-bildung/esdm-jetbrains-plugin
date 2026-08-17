package net.edditech.esdm.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolver logic without the IDE fixture: the schema is plain data, so its
 * behaviour is worth pinning down in isolation from PSI and completion.
 */
class EsdmSchemaResolverTest {

    private val core = mapOf("apiVersion" to "schema.esdm.io/core/v1")

    private fun names(document: Map<String, Any?>, path: List<String>) =
        EsdmSchema.propertiesAt(document, path).map { it.name }.sorted()

    private fun values(document: Map<String, Any?>, path: List<String>) =
        EsdmSchema.valuesAt(document, path).map { it.value }.sorted()

    @Test
    fun `apiVersion selects the branch`() {
        assertEquals(
            listOf("schema.esdm.io/core/v1", "schema.esdm.io/domain-storytelling/v1", "schema.esdm.io/given-when-then/v1"),
            EsdmSchema.apiVersions.sorted(),
        )
        assertEquals(listOf("feature"), values(mapOf("apiVersion" to "schema.esdm.io/given-when-then/v1"), listOf("kind")))
    }

    @Test
    fun `kind selects the if-then branch`() {
        val aggregate = names(core + ("kind" to "aggregate"), emptyList())
        assertTrue("expected aggregate fields, got $aggregate", aggregate.containsAll(listOf("identifiedBy", "state")))
        assertTrue("publishes belongs to command, not aggregate", "publishes" !in aggregate)
    }

    @Test
    fun `nested path resolves through a ref`() {
        assertEquals(
            listOf("boundedContext", "domain"),
            names(core + ("kind" to "aggregate"), listOf("scope")),
        )
    }

    @Test
    fun `oneOf collects discriminator constants`() {
        assertEquals(
            listOf(
                "anti-corruption-layer", "conformist", "customer-supplier", "open-host-service",
                "partnership", "published-language", "separate-ways", "shared-kernel",
            ),
            values(core + ("kind" to "context-mapping"), listOf("type")),
        )
    }

    @Test
    fun `oneOf narrows once the discriminator is known`() {
        val document = core + mapOf("kind" to "context-mapping", "type" to "published-language")
        val offered = names(document, emptyList())

        assertTrue("expected publisher/consumer, got $offered", offered.containsAll(listOf("publisher", "consumer")))
        assertTrue("participants belongs to the symmetric variants", "participants" !in offered)
    }

    @Test
    fun `unknown apiVersion yields nothing`() {
        assertEquals(emptyList<String>(), names(mapOf("apiVersion" to "nope"), emptyList()))
    }
}
