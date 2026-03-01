package com.example.open.reviewer.commitchecklist.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserAcceptanceTest {
    private val parser = CommitChecklistSpecParser()

    @Test
    fun `provided template parses into expected sections`() {
        val markdown =
            """
            <!-- openreviewer:version=1 -->
            ## Description
            Describe what changed and why.

            ## Type of Change
            - Feature
            - Bug fix

            ## Checklist
            - [ ] Added tests
            - [ ] Self-review completed

            ## Reviewer’s Guidance
            Focus review on risk and rollout impact.
            """.trimIndent()

        val spec = parser.parse(markdown)

        assertEquals("Describe what changed and why.", spec.descriptionTemplate)
        assertEquals(listOf("Feature", "Bug fix"), spec.typeOptions)
        assertEquals(2, spec.checklistItems.size)
        assertEquals("Focus review on risk and rollout impact.", spec.reviewersGuidance)
    }

    @Test
    fun `checked boxes parse for lowercase and uppercase x`() {
        val markdown =
            """
            ## Checklist
            - [x] Lower checked
            - [X] Upper checked
            - [ ] Unchecked
            """.trimIndent()

        val spec = parser.parse(markdown)

        assertEquals(3, spec.checklistItems.size)
        assertTrue(spec.checklistItems[0].checked)
        assertTrue(spec.checklistItems[1].checked)
        assertEquals(false, spec.checklistItems[2].checked)
    }

    @Test
    fun `malformed markdown is handled safely`() {
        val malformed = "## Type of Change\n- [x\n## Checklist\n- [\n- ]\n###"

        val spec = parser.parse(malformed)

        assertEquals("1", spec.version)
        assertEquals("", spec.descriptionTemplate)
        assertEquals("", spec.reviewersGuidance)
    }
}
