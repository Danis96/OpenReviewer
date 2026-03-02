package com.example.open.reviewer.commitchecklist.gate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitChecklistCommitMessageParserTest {
    @Test
    fun `returns true when required fields are present`() {
        val message =
            """
            feat: add new flow

            [openreviewer]
            type=Feature
            checklist=tests,self_review,docs
            description=Implemented with migration guard
            [/openreviewer]
            """.trimIndent()

        val complete =
            CommitChecklistCommitMessageParser.hasCompleteChecklistMetadata(
                commitMessage = message,
                requireDescription = true,
            )

        assertTrue(complete)
    }

    @Test
    fun `returns false when block is missing`() {
        val complete =
            CommitChecklistCommitMessageParser.hasCompleteChecklistMetadata(
                commitMessage = "fix: patch",
                requireDescription = false,
            )

        assertFalse(complete)
    }

    @Test
    fun `returns false when type or checklist is missing`() {
        val message =
            """
            fix: patch

            [openreviewer]
            checklist=tests
            [/openreviewer]
            """.trimIndent()

        val complete =
            CommitChecklistCommitMessageParser.hasCompleteChecklistMetadata(
                commitMessage = message,
                requireDescription = false,
            )

        assertFalse(complete)
    }

    @Test
    fun `description can be optional by mode`() {
        val message =
            """
            fix: patch

            [openreviewer]
            type=Bug fix
            checklist=tests
            [/openreviewer]
            """.trimIndent()

        val optional =
            CommitChecklistCommitMessageParser.hasCompleteChecklistMetadata(
                commitMessage = message,
                requireDescription = false,
            )
        val required =
            CommitChecklistCommitMessageParser.hasCompleteChecklistMetadata(
                commitMessage = message,
                requireDescription = true,
            )

        assertTrue(optional)
        assertFalse(required)
    }
}
