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
 *
 * Environment note: the tests that read the bundled schema fail with
 * `VfsRootAccessNotAllowedError` if the project lives under `/tmp` on macOS —
 * the test framework canonicalises to `/private/tmp` and the sandbox then falls
 * outside the allowed VFS roots. That is the checkout location, not the code;
 * building from a normal path passes.
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

    // The platform's completion behaviour used to be asserted here as a canary,
    // on the theory that it would fail once JetBrains fixed the engine. That
    // stopped being observable the moment EsdmCompletionContributor started
    // calling stopHere() on `*.esdm.yaml` — the platform never gets to answer,
    // so the canary could only ever measure our own contributor.
    //
    // The behaviour it recorded, measured on platform 252:
    //   - properties inside `kind: aggregate` → all 50 properties from every
    //     kind and both extension schemas
    //   - `type:` inside `kind: context-mapping` → `[human, system]`, the actor
    //     enum, i.e. the wrong branch rather than merely too many branches
    //
    // To re-measure against a newer platform, comment out the
    // completion.contributor registration in plugin.xml and run
    // EsdmCompletionTest: if it still passes, the engine has been fixed and the
    // contributor can be retired. Guarding the behaviour we ship is
    // EsdmCompletionTest's job, not this class's.
}
