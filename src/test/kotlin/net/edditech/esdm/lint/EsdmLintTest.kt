package net.edditech.esdm.lint

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class EsdmLintTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    // ------------------------------------------------------------ CLI contract

    /**
     * The measured contract: exit status alone cannot distinguish "errors found"
     * from "the tool broke", because both exit 1. Only stdout can — findings
     * always arrive as a JSON array, a failure leaves stdout empty. Reading the
     * exit code instead would report a broken tool as a clean model.
     */
    fun testFindingsAreParsed() {
        val outcome = EsdmCli.parse(
            """
            [
              {
                "ruleId": "esdm/modeling/orphan-context-mapping",
                "severity": "warning",
                "message": "context-mapping \"a-to-b\" links bounded contexts",
                "location": { "file": "integration/mappings.esdm.yaml", "line": 3, "column": 7 }
              }
            ]
            """.trimIndent(),
        )

        val finding = assertOneElement(outcome!!.findings)
        assertEquals("esdm/modeling/orphan-context-mapping", finding.ruleId)
        assertEquals(EsdmSeverity.WARNING, finding.severity)
        assertEquals("integration/mappings.esdm.yaml", finding.file)
        assertEquals(3, finding.line)
        assertEquals(7, finding.column)
    }

    fun testCleanModelIsAnEmptyArrayNotAFailure() {
        val outcome = EsdmCli.parse("[]")
        assertNotNull("an empty array means a clean model", outcome)
        assertEmpty(outcome!!.findings)
    }

    fun testToolFailureIsDistinguishableFromCleanRun() {
        // Empty stdout is what a failure looks like; the message goes to stderr.
        assertNull(EsdmCli.parse(""))
        assertNull(EsdmCli.parse("Error: cannot access \"/nope\""))
    }

    fun testErrorSeverityIsMapped() {
        val outcome = EsdmCli.parse(
            """[{"ruleId":"esdm/structure/unresolved-reference","severity":"error","message":"x",
               "location":{"file":"a.esdm.yaml","line":1,"column":1}}]""",
        )
        assertEquals(EsdmSeverity.ERROR, outcome!!.findings.single().severity)
    }

    // --------------------------------------------------------- range clamping

    private fun documentOf(text: String) =
        myFixture.configureByText("probe.esdm.yaml", text).viewProvider.document!!

    fun testRangeCoversFromColumnToEndOfLine() {
        val document = documentOf("apiVersion: x\nkind: aggregate\n")
        val range = finding(line = 2, column = 7).rangeIn(document)!!

        assertEquals("aggregate", document.charsSequence.subSequence(range.startOffset, range.endOffset).toString())
    }

    /**
     * The CLI's view of the file can be a moment behind the editor's. An
     * out-of-range TextRange throws and takes the whole highlighting pass down
     * with it, so the invariant is "never throws" — skipping a finding whose
     * coordinates no longer mean anything is the correct outcome, not a bug.
     */
    fun testOutOfRangeCoordinatesNeverThrow() {
        val document = documentOf("kind: aggregate\n")

        finding(line = 999, column = 1).rangeIn(document)
        finding(line = 0, column = 0).rangeIn(document)
        finding(line = -5, column = -5).rangeIn(document)

        // A column past the end still has a line to fall back to.
        assertNotNull(finding(line = 1, column = 999).rangeIn(document))
    }

    fun testEmptyDocumentYieldsNoRange() {
        assertNull(finding(line = 1, column = 1).rangeIn(documentOf("")))
    }

    // ------------------------------------------------------------ suppression

    fun testLineScopedSuppression() {
        val document = documentOf(
            """
            kind: context-mapping
            # esdm-lint-disable esdm/modeling/orphan-context-mapping
            name: a-to-b
            """.trimIndent(),
        )
        assertTrue(finding(line = 3, rule = "esdm/modeling/orphan-context-mapping").isSuppressedIn(document))
    }

    /** Rule-scoped on purpose: a different rule on the same line still reports. */
    fun testSuppressionIsRuleScoped() {
        val document = documentOf(
            """
            kind: context-mapping
            # esdm-lint-disable esdm/modeling/orphan-context-mapping
            name: a-to-b
            """.trimIndent(),
        )
        assertFalse(finding(line = 3, rule = "esdm/structure/duplicate-name").isSuppressedIn(document))
    }

    fun testFileScopedSuppression() {
        val document = documentOf(
            """
            # esdm-lint-disable-file esdm/modeling/orphan-context-mapping
            kind: context-mapping
            name: a-to-b
            """.trimIndent(),
        )
        assertTrue(finding(line = 3, rule = "esdm/modeling/orphan-context-mapping").isSuppressedIn(document))
    }

    fun testUnsuppressedFindingIsReported() {
        val document = documentOf("kind: context-mapping\nname: a-to-b\n")
        assertFalse(finding(line = 2).isSuppressedIn(document))
    }

    // ------------------------------------------------------- model root

    fun testModelRootPrefersTheSchemasMarkerOrTheHighestModelFolder() {
        val root = myFixture.copyDirectoryToProject("library", "library")
        val nested = root.findFileByRelativePath("lending/copy.esdm.yaml")!!

        val resolved = EsdmLintService.getInstance(project).modelRootFor(nested)

        // `library/` holds domain.esdm.yaml, so it is the highest folder that
        // still contains documents — linting from `lending/` would report every
        // cross-context reference as unresolved.
        assertEquals(root.path, resolved!!.path)
    }

    // ------------------------------------------------- end-to-end, if available

    /**
     * Runs the real binary when there is one. Skipped rather than failed when
     * absent, because CI has no `esdm` and this is an integration check, not a
     * gate — [testFindingsAreParsed] covers the contract without it.
     */
    fun testRealBinaryLintsTheFixtureCleanly() {
        val executable = File("esdm").absoluteFile
        if (!executable.canExecute()) {
            println("skipping: no esdm binary at ${executable.path}")
            return
        }

        // Deliberately the fixture on disk, not a copy in the test project: the
        // light fixture's VFS is in-memory, so an external process has nothing
        // to read. The one thing this test exists to exercise is a real process
        // against real files.
        val onDisk = File("src/test/testData/library").absoluteFile
        val root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(onDisk)!!
        val outcome = EsdmCli.lint(executable, root)

        assertInstanceOf(outcome, EsdmCli.Outcome.Findings::class.java)
        assertEmpty(
            "the fixture must stay lint-clean",
            (outcome as EsdmCli.Outcome.Findings).findings,
        )
    }

    /**
     * The other half of the integration check: that a real defect comes back
     * parsed, located and correctly classified. "Clean model stays clean" alone
     * would still pass if the output were being silently dropped.
     */
    fun testRealBinaryReportsABrokenModel() {
        val executable = File("esdm").absoluteFile
        if (!executable.canExecute()) {
            println("skipping: no esdm binary at ${executable.path}")
            return
        }

        val directory = createTempDirectory()
        File(directory, "domain.esdm.yaml").writeText(
            """
            apiVersion: schema.esdm.io/core/v1
            kind: subdomain
            name: nowhere
            scope:
              domain: library
            type: core
            boundedContexts:
              - does-not-exist
            """.trimIndent(),
        )

        val root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        val outcome = EsdmCli.lint(executable, root)

        assertInstanceOf(outcome, EsdmCli.Outcome.Findings::class.java)
        val findings = (outcome as EsdmCli.Outcome.Findings).findings
        assertTrue("expected findings for a model with a dangling reference", findings.isNotEmpty())

        val unresolved = findings.first { it.ruleId.contains("unresolved-reference") }
        assertEquals(EsdmSeverity.ERROR, unresolved.severity)
        assertEquals("domain.esdm.yaml", unresolved.file.removePrefix("./"))
        assertTrue("expected a usable line number, got ${unresolved.line}", unresolved.line >= 1)
    }

    private fun createTempDirectory(): File =
        File.createTempFile("esdm-lint-test", "").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe.deleteOnExit()
            probe
        }

    private fun finding(line: Int, column: Int = 1, rule: String = "esdm/modeling/some-rule") =
        EsdmFinding(rule, EsdmSeverity.WARNING, "message", "probe.esdm.yaml", line, column)
}
