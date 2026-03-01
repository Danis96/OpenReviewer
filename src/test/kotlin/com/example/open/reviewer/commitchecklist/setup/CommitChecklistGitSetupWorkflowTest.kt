package com.example.open.reviewer.commitchecklist.setup

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class CommitChecklistGitSetupWorkflowTest {
    @Test
    fun `buildStageArgs uses repo relative unix style paths`() {
        val gitRoot = Path.of("/repo")
        val files =
            listOf(
                Path.of("/repo/.openreviewer/COMMIT_CHECKLIST.md"),
                Path.of("/repo/.github/pull_request_template.md"),
            )

        val args = CommitChecklistGitSetupWorkflow.buildStageArgs(gitRoot, files)

        assertEquals(
            listOf(
                "add",
                ".openreviewer/COMMIT_CHECKLIST.md",
                ".github/pull_request_template.md",
            ),
            args,
        )
    }
}
