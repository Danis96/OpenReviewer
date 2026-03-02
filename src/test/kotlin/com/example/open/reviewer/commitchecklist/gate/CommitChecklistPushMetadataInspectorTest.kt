package com.example.open.reviewer.commitchecklist.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitChecklistPushMetadataInspectorTest {
    @Test
    fun `inspect marks missing commits without metadata`() {
        val messages =
            listOf(
                "fix: no checklist",
                """
                feat: done

                [openreviewer]
                type=Feature
                checklist=tests,self_review
                [/openreviewer]
                """.trimIndent(),
            )
        val summaries = listOf("fix: no checklist", "feat: done")

        val result =
            CommitChecklistPushMetadataInspector.inspect(
                commitMessages = messages,
                commitSummaries = summaries,
                requireDescription = false,
            )

        assertEquals(listOf("fix: no checklist"), result.missingCommitSummaries)
    }

    @Test
    fun `inspect returns complete when all commits contain metadata`() {
        val messages =
            listOf(
                """
                feat: one

                [openreviewer]
                type=Feature
                checklist=tests
                description=done
                [/openreviewer]
                """.trimIndent(),
                """
                fix: two

                [openreviewer]
                type=Bug fix
                checklist=tests
                description=done
                [/openreviewer]
                """.trimIndent(),
            )
        val summaries = listOf("feat: one", "fix: two")

        val result =
            CommitChecklistPushMetadataInspector.inspect(
                commitMessages = messages,
                commitSummaries = summaries,
                requireDescription = true,
            )

        assertTrue(result.isComplete)
        assertTrue(result.missingCommitSummaries.isEmpty())
    }
}
