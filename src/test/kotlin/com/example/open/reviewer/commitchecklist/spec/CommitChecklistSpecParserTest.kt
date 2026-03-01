package com.example.open.reviewer.commitchecklist.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class CommitChecklistSpecParserTest {
    private val parser = CommitChecklistSpecParser()

    @Test
    fun `parses complete checklist template into internal model`() {
        val markdown =
            """
            <!-- openreviewer:version=1 -->
            ## Description
            Explain what this change does and why.

            ## Type of Change
            - Bug fix
            - Feature
            - Refactor

            ## Checklist
            - [ ] Added tests
            - [x] Updated docs
            - [X] Self review done

            ## Reviewer’s Guidance
            Focus on risk, migrations, and backward compatibility.
            """.trimIndent()

        val sourcePath = Path.of("/repo/.openreviewer/COMMIT_CHECKLIST.md")
        val spec = parser.parse(markdown, sourcePath)

        assertEquals("Explain what this change does and why.", spec.descriptionTemplate)
        assertEquals(listOf("Bug fix", "Feature", "Refactor"), spec.typeOptions)
        assertEquals(3, spec.checklistItems.size)
        assertFalse(spec.checklistItems[0].checked)
        assertTrue(spec.checklistItems[1].checked)
        assertTrue(spec.checklistItems[2].checked)
        assertEquals("1", spec.version)
        assertEquals(sourcePath, spec.sourcePath)
        assertEquals("Focus on risk, migrations, and backward compatibility.", spec.reviewersGuidance)
    }

    @Test
    fun `parser tolerates heading and whitespace variants`() {
        val markdown =
            """
            ###   Description   

              Short description with spaces.  

            ## Type   of   Change
            * Maintenance
            *   Chore

            ## Checklist
            - [ ]  First item
            - [X] Second item   

            ## Reviewer's Guidance
            Please verify edge cases.
            """.trimIndent()

        val spec = parser.parse(markdown)

        assertEquals("Short description with spaces.", spec.descriptionTemplate)
        assertEquals(listOf("Maintenance", "Chore"), spec.typeOptions)
        assertEquals(2, spec.checklistItems.size)
        assertFalse(spec.checklistItems[0].checked)
        assertTrue(spec.checklistItems[1].checked)
        assertEquals("Please verify edge cases.", spec.reviewersGuidance)
    }

    @Test
    fun `malformed markdown never throws and returns safe defaults`() {
        val markdown = "## Checklist\n- [x\n- [ ]"

        val spec = parser.parse(markdown)

        assertEquals("", spec.descriptionTemplate)
        assertEquals(emptyList<String>(), spec.typeOptions)
        assertEquals(emptyList<ChecklistItem>(), spec.checklistItems)
        assertEquals("", spec.reviewersGuidance)
        assertEquals("1", spec.version)
    }
}
