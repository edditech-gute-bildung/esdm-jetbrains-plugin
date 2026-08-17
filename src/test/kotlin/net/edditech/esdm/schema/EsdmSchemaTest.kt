package net.edditech.esdm.schema

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.schema.YamlJsonSchemaHighlightingInspection

/**
 * What the platform's JSON Schema engine actually does with the ESDM schema.
 *
 * The short version, measured rather than assumed: **validation discriminates
 * correctly, completion does not.** Validation picks the right `oneOf` branch
 * from `apiVersion` and the right `if`/`then` branch from `kind`. Completion
 * ignores both and unions every branch in the document — see
 * [IJPL-63615](https://youtrack.jetbrains.com/issue/IJPL-63615).
 *
 * The tests below that assert broken behaviour are canaries, not endorsements:
 * if a platform release fixes the engine they will fail, and that is the signal
 * that our own completion contributor can be retired.
 */
class EsdmSchemaTest : BasePlatformTestCase() {

    // ---------------------------------------------------------------- validation

    fun testUnknownKindIsReported() {
        myFixture.enableInspections(YamlJsonSchemaHighlightingInspection())
        myFixture.configureByText(
            "broken.esdm.yaml",
            """
            apiVersion: schema.esdm.io/core/v1
            kind: nonsense
            name: x
            """.trimIndent(),
        )

        val warning = myFixture.doHighlighting(HighlightSeverity.WARNING)
            .singleOrNull { it.description?.contains("Value should be one of") == true }
        assertNotNull("expected the unknown kind to be flagged", warning)

        // The message lists exactly the 18 core kinds and neither `feature` nor
        // `domain-story` — proof that validation resolved the `oneOf` on
        // `apiVersion` to the core branch rather than merging all three.
        val description = warning!!.description
        assertTrue(description.contains("\"aggregate\""))
        assertFalse("extension kinds must not leak into core validation", description.contains("\"feature\""))
        assertFalse(description.contains("\"domain-story\""))
    }

    fun testValidDocumentIsClean() {
        myFixture.enableInspections(YamlJsonSchemaHighlightingInspection())
        myFixture.configureByText(
            "domain.esdm.yaml",
            """
            apiVersion: schema.esdm.io/core/v1
            kind: domain
            name: library
            """.trimIndent(),
        )
        myFixture.testHighlighting(true, false, true)
    }

    /** `if`/`then` on `kind` is enforced: an aggregate without `state` is invalid. */
    fun testKindSpecificRequiredFieldsAreEnforced() {
        myFixture.enableInspections(YamlJsonSchemaHighlightingInspection())
        myFixture.configureByText(
            "book.esdm.yaml",
            """
            apiVersion: schema.esdm.io/core/v1
            kind: aggregate
            name: book
            scope:
              domain: library
              boundedContext: cataloging
            """.trimIndent(),
        )

        val missing = myFixture.doHighlighting(HighlightSeverity.WARNING)
            .mapNotNull { it.description }
            .filter { it.contains("Missing required propert", ignoreCase = true) }
        assertTrue(
            "expected identifiedBy/state to be reported missing, got $missing",
            missing.any { it.contains("identifiedBy") || it.contains("state") },
        )
    }

    // ---------------------------------------------------------------- completion

    /**
     * CANARY. Property completion ignores both discriminators and offers the
     * union of every kind's properties: asking inside an `aggregate` yields
     * `capabilities`, `scenarios`, `readModel` and 47 others. Unusable as-is,
     * which is why [net.edditech.esdm.completion] exists.
     */
    fun testPropertyCompletionIsUndiscriminated() {
        myFixture.configureByText(
            "book.esdm.yaml",
            """
            apiVersion: schema.esdm.io/core/v1
            kind: aggregate
            name: book
            <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val offered = myFixture.lookupElementStrings.orEmpty().toSet()

        // Asserted as a set relation rather than an exact list, so the test
        // survives cosmetic changes in ordering or in unrelated kinds and only
        // flips when the engine genuinely starts discriminating.
        val foreign = setOf("capabilities", "readModel", "scenarios", "sentences")
        assertTrue(
            "completion should still be leaking foreign properties; leaked=${offered intersect foreign}",
            (offered intersect foreign).isNotEmpty(),
        )
    }

    /**
     * CANARY, and the worst of it: enum completion does not merely over-offer,
     * it picks the *wrong* branch. A `context-mapping`'s `type` should offer the
     * eight DDD mapping patterns; the engine offers the `actor` enum instead.
     * Silently wrong suggestions are worse than none, so our contributor has to
     * replace these rather than merely add to them.
     */
    fun testEnumCompletionResolvesToTheWrongBranch() {
        myFixture.configureByText(
            "mapping.esdm.yaml",
            """
            apiVersion: schema.esdm.io/core/v1
            kind: context-mapping
            name: a-to-b
            type: <caret>
            """.trimIndent(),
        )
        myFixture.completeBasic()
        val offered = myFixture.lookupElementStrings.orEmpty().toSet()

        // The invariant we actually depend on: the correct values are absent.
        // Stated this way rather than pinning the exact wrong list, so the test
        // reports "the engine got fixed" instead of "the wrong answer changed".
        assertFalse(
            "context-mapping patterns should still be missing; offered=$offered",
            offered.containsAll(listOf("published-language", "customer-supplier")),
        )
    }
}
