package net.edditech.esdm.completion

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The contributor exists because the platform's schema completion is wrong
 * inside ESDM documents (see EsdmSchemaTest). These tests assert the two
 * measured defects are gone, and that discrimination the platform cannot do at
 * all now works.
 */
class EsdmCompletionTest : BasePlatformTestCase() {

    private fun complete(text: String): List<String> {
        myFixture.configureByText("probe.esdm.yaml", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    // ------------------------------------------------------- the two regressions

    /** Was: all 50 properties from every kind and every apiVersion. */
    fun testAggregatePropertiesAreDiscriminated() {
        val offered = complete(
            """
            apiVersion: schema.esdm.io/core/v1
            kind: aggregate
            name: book
            <caret>
            """.trimIndent(),
        )

        assertContainsElements(offered, "identifiedBy", "state", "invariants", "scope")
        assertDoesntContain(offered, "capabilities", "readModel", "scenarios", "sentences", "publishes")
    }

    /** Was: `[human, system]` — the actor enum, for a context-mapping. */
    fun testContextMappingTypeOffersMappingPatterns() {
        val offered = complete(
            """
            apiVersion: schema.esdm.io/core/v1
            kind: context-mapping
            name: a-to-b
            type: <caret>
            """.trimIndent(),
        )

        assertSameElements(
            offered,
            "anti-corruption-layer", "conformist", "customer-supplier", "open-host-service",
            "partnership", "published-language", "separate-ways", "shared-kernel",
        )
    }

    // ----------------------------------------------------------- discrimination

    fun testCommandPropertiesDifferFromAggregate() {
        val offered = complete(
            """
            apiVersion: schema.esdm.io/core/v1
            kind: command
            name: acquire
            <caret>
            """.trimIndent(),
        )

        assertContainsElements(offered, "publishes", "data", "actors")
        assertDoesntContain(offered, "identifiedBy", "state", "projections")
    }

    fun testKindValuesComeFromTheMatchingApiVersion() {
        assertEquals(18, complete(
            """
            apiVersion: schema.esdm.io/core/v1
            kind: <caret>
            """.trimIndent(),
        ).size)

        assertEquals(
            listOf("feature"),
            complete(
                """
                apiVersion: schema.esdm.io/given-when-then/v1
                kind: <caret>
                """.trimIndent(),
            ),
        )
    }

    /** Dispatch keys on `apiVersion`; `.feature.esdm.yaml` is only a convention. */
    fun testDispatchIgnoresFilename() {
        myFixture.configureByText(
            "plainly-named.esdm.yaml",
            """
            apiVersion: schema.esdm.io/domain-storytelling/v1
            kind: <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()

        assertEquals(listOf("domain-story"), myFixture.lookupElementStrings)
    }

    /**
     * `oneOf` narrows once its discriminator is known: having chosen
     * `published-language`, only that variant's endpoints are valid. The
     * platform offers the union regardless.
     */
    fun testOneOfNarrowsOnceTheDiscriminatorIsSet() {
        val offered = complete(
            """
            apiVersion: schema.esdm.io/core/v1
            kind: context-mapping
            name: a-to-b
            type: published-language
            <caret>
            """.trimIndent(),
        )

        assertContainsElements(offered, "publisher", "consumer")
        assertDoesntContain(offered, "participants", "conformist", "customer", "supplier")
    }

    fun testNestedPathResolves() {
        val offered = complete(
            """
            apiVersion: schema.esdm.io/core/v1
            kind: aggregate
            name: book
            scope:
              <caret>
            """.trimIndent(),
        )

        assertSameElements(offered, "domain", "boundedContext")
    }

    // ------------------------------------------------------------------ hygiene

    fun testAlreadyWrittenKeysAreNotOfferedAgain() {
        val offered = complete(
            """
            apiVersion: schema.esdm.io/core/v1
            kind: aggregate
            name: book
            state:
              type: object
            <caret>
            """.trimIndent(),
        )

        assertDoesntContain(offered, "state", "kind", "name", "apiVersion")
        assertContainsElements(offered, "identifiedBy")
    }

    /**
     * An unknown apiVersion is not ours to complete, so we defer rather than
     * guess. Asserted as "we did not produce the core kind list", because
     * whatever the platform then offers is its business and not a contract —
     * an earlier version of this test pinned the platform's output and broke
     * when the schema's dispatch shape changed, which is precisely the kind of
     * coupling worth avoiding.
     */
    fun testUnknownApiVersionIsNotGuessedAt() {
        val offered = complete(
            """
            apiVersion: schema.esdm.io/does-not-exist/v9
            kind: <caret>
            """.trimIndent(),
        ).toSet()

        val coreKinds = complete(
            """
            apiVersion: schema.esdm.io/core/v1
            kind: <caret>
            """.trimIndent(),
        ).toSet()

        assertFalse("an unrecognised apiVersion must not be completed as core", offered == coreKinds)
    }

    /** The merged schema pins apiVersion to an enum, so a typo in it is an error. */
    fun testUnknownApiVersionIsReportedByValidation() {
        myFixture.enableInspections(org.jetbrains.yaml.schema.YamlJsonSchemaHighlightingInspection())
        myFixture.configureByText(
            "typo.esdm.yaml",
            """
            apiVersion: schema.esdm.io/core/v2
            kind: domain
            name: library
            """.trimIndent(),
        )

        val problems = myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.WARNING)
            .mapNotNull { it.description }
        assertTrue("expected the unknown apiVersion to be flagged, got $problems", problems.isNotEmpty())
    }
}
