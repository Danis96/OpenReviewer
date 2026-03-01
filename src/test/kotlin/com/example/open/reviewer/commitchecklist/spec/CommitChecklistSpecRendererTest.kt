package com.example.open.reviewer.commitchecklist.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class CommitChecklistSpecRendererTest {
    private val renderer = CommitChecklistSpecRenderer()
    private val parser = CommitChecklistSpecParser()

    @Test
    fun `renderer outputs canonical deterministic markdown`() {
        val spec =
            ChecklistSpec(
                descriptionTemplate = "  Implement the new flow.  ",
                typeOptions = listOf("Feature", "Bug fix"),
                checklistItems =
                    listOf(
                        ChecklistItem(text = "Added tests", checked = false),
                        ChecklistItem(text = "Updated docs", checked = true),
                    ),
                reviewersGuidance = "  Focus on edge cases.  ",
                version = "2",
                sourcePath = Path.of("/repo/.openreviewer/COMMIT_CHECKLIST.md"),
            )

        val rendered = renderer.render(spec)

        val expected =
            """
            <!-- openreviewer:version=2 -->

            ## Description
            Implement the new flow.

            ## Type of Change
            - Feature
            - Bug fix

            ## Checklist
            - [ ] Added tests
            - [x] Updated docs

            ## Reviewer’s Guidance
            Focus on edge cases.
            """.trimIndent() + "\n"

        assertEquals(expected, rendered)
    }

    @Test
    fun `round trip remains stable`() {
        val input =
            """
            <!-- openreviewer:version=1 -->

            ## Description
            Explain scope.

            ## Type of Change
            - Refactor
            - Chore

            ## Checklist
            - [ ] Added tests
            - [X] Updated docs

            ## Reviewer's Guidance
            Verify migration path.
            """.trimIndent()

        val parsed = parser.parse(input, sourcePath = Path.of("/repo/COMMIT_CHECKLIST.md"))
        val rendered = renderer.render(parsed)
        val reparsed = parser.parse(rendered, sourcePath = Path.of("/repo/COMMIT_CHECKLIST.md"))

        assertEquals(parsed.copy(sourcePath = Path.of("/repo/COMMIT_CHECKLIST.md")), reparsed)
        assertTrue(rendered.contains("- [x] Updated docs"))
    }

    @Test
    fun `renderer normalizes checked format to lowercase x`() {
        val spec =
            ChecklistSpec(
                descriptionTemplate = "",
                typeOptions = emptyList(),
                checklistItems = listOf(ChecklistItem(text = "Self review", checked = true)),
                reviewersGuidance = "",
                version = "1",
                sourcePath = null,
            )

        val rendered = renderer.render(spec)

        assertTrue(rendered.contains("- [x] Self review"))
    }
}
