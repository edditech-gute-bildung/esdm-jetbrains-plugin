package net.edditech.esdm.index

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Processor

/**
 * Gutter markers and Go to Symbol.
 *
 * Both go through the platform: gutters via `myFixture.findAllGutters`, which is
 * what the editor renders, and symbols via the contributor's own processor
 * contract. Testing the classes directly would prove they compute something and
 * not that anything ever asks them — the failure mode this project has hit
 * repeatedly.
 */
class EsdmNavigationExtrasTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    // ---------------------------------------------------------- gutter markers

    private fun guttersIn(relativePath: String): List<String> {
        val root = myFixture.copyDirectoryToProject("library", "library")
        myFixture.openFileInEditor(root.findFileByRelativePath(relativePath)!!)
        return myFixture.findAllGutters().mapNotNull(GutterMark::getTooltipText)
    }

    fun testACommandLinksToTheEventsItPublishes() {
        val tooltips = guttersIn("cataloging/book.esdm.yaml")

        assertTrue(
            "expected a gutter linking acquire to acquired, got $tooltips",
            tooltips.any { it.contains("Publishes") && it.contains("acquired") },
        )
    }

    fun testAnEventLinksBackToWhatProducesAndConsumesIt() {
        val tooltips = guttersIn("cataloging/book.esdm.yaml")

        // `acquired` is published by the acquire command, projected by the books
        // read model, and handled by the policy that stocks a copy.
        val consumed = tooltips.filter { it.startsWith("Produced or consumed by") }
        assertTrue("expected a consumer gutter, got $tooltips", consumed.isNotEmpty())
        assertTrue(
            "expected the publishing command among them, got $consumed",
            consumed.any { it.contains("command acquire") },
        )
    }

    /** A declaration nothing points at should not get a marker at all. */
    fun testNoMarkerWhereThereIsNothingToShow() {
        val tooltips = guttersIn("reservations/reservation-limit.esdm.yaml")

        assertFalse(
            "list-reservations is referenced by nothing and needs no gutter",
            tooltips.any { it.contains("list-reservations") },
        )
    }

    // ------------------------------------------------------------ go to symbol

    private fun symbolNames(): List<String> {
        myFixture.copyDirectoryToProject("library", "library")
        val names = mutableListOf<String>()
        EsdmSymbolContributor().processNames(
            Processor { names += it; true },
            GlobalSearchScope.allScope(project),
            null,
        )
        return names
    }

    fun testGoToSymbolOffersEveryDeclaration() {
        val names = symbolNames()

        // One from each kind, across three bounded contexts.
        assertContainsElements(
            names,
            "library",          // domain
            "cataloging",       // bounded context
            "book",             // aggregate
            "acquired",         // event
            "acquire",          // command
            "books",            // read model
            "reservation-limit" // dynamic consistency boundary
        )
    }

    fun testGoToSymbolExcludesNonDeclarations() {
        val names = symbolNames()

        // Feature scenarios have a `name`, but a feature is not an artifact with
        // an identity in the model — only documents declaring a `kind` count.
        assertDoesntContain(names, "neuer-pfad-erlaubt-nur-den-ersten-ort", "only-available-copies-can-be-borrowed")
    }

    fun testGoToSymbolResolvesToANavigableTarget() {
        myFixture.copyDirectoryToProject("library", "library")
        val found = mutableListOf<String>()

        EsdmSymbolContributor().processElementsWithName(
            "acquired",
            Processor { item -> found += "${item.name} @ ${item.presentation?.locationString ?: "?"}"; true },
            com.intellij.util.indexing.FindSymbolParameters.simple(project, false),
        )

        assertTrue("expected a navigable item for `acquired`, got $found", found.isNotEmpty())
    }
}
