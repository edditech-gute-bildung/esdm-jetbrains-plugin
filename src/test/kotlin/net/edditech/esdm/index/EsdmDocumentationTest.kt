package net.edditech.esdm.index

import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Hovering a reference should describe what it points at, not the schema's
 * description of the field it sits in.
 */
class EsdmDocumentationTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    /** Documentation for whatever the reference under the caret resolves to. */
    private fun documentationForTargetAt(relativePath: String, snippet: String): String {
        val root = myFixture.copyDirectoryToProject("library", "library")
        myFixture.openFileInEditor(root.findFileByRelativePath(relativePath)!!)

        val offset = myFixture.editor.document.text.indexOf(snippet)
        assertTrue("snippet not found: $snippet", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset + snippet.length - 1)

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as? PsiPolyVariantReference
        val target = reference?.multiResolve(false)?.firstOrNull()?.element as? YAMLScalar
        assertNotNull("expected the reference to resolve", target)

        val documentation = EsdmDocumentationProvider().documentationTarget(target!!, null)
        assertNotNull("expected documentation for the target", documentation)
        return computeDocumentationBlocking(documentation!!.createPointer())?.html.orEmpty()
    }

    fun testDomainHoverShowsTheDomainsOwnDetails() {
        val html = documentationForTargetAt("cataloging/book.esdm.yaml", "domain: library")

        assertTrue("expected the kind and name, got: $html", html.contains("domain") && html.contains("library"))
        // The domain's own description, not the schema's description of `domain:`.
        assertTrue("expected the declaration's description, got: $html", html.contains("A city library"))
    }

    fun testBoundedContextHoverShowsScope() {
        val html = documentationForTargetAt("integration/integration.esdm.yaml", "boundedContext: lending")

        assertTrue("expected the bounded context, got: $html", html.contains("lending"))
        assertTrue("expected its scope, got: $html", html.contains("domain: library"))
    }

    fun testAggregateHoverShowsInvariants() {
        val html = documentationForTargetAt("lending/views.esdm.yaml", "aggregate: copy")

        assertTrue("expected invariant names, got: $html", html.contains("only-available-copies-can-be-borrowed"))
    }

    fun testCommandHoverShowsWhatItPublishes() {
        val html = documentationForTargetAt("integration/integration.esdm.yaml", "command: stock")

        assertTrue("expected the published event, got: $html", html.contains("stocked"))
    }

    fun testEventHoverIdentifiesItsOwner() {
        val html = documentationForTargetAt("integration/integration.esdm.yaml", "event: acquired")

        assertTrue("expected the owning aggregate in scope, got: $html", html.contains("aggregate: book"))
    }

    /** Free-standing events have no owner, and their scope should say so by omission. */
    fun testFreeStandingEventHoverHasNoAggregate() {
        val html = documentationForTargetAt("reservations/reservation-limit.esdm.yaml", "event: reserved")

        assertTrue("expected the reservations context, got: $html", html.contains("boundedContext: reservations"))
        assertFalse("a free-standing event has no owning aggregate, got: $html", html.contains("aggregate:"))
    }
}
