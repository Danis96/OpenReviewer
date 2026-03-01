package com.example.open.reviewer.commitchecklist.template

import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateDiffBuilderTest {
    @Test
    fun `buildUnifiedDiff includes headers and change lines`() {
        val diff =
            TemplateDiffBuilder.buildUnifiedDiff(
                path = ".gitlab/merge_request_templates/OpenReviewer.md",
                oldText = "A\nB\n",
                newText = "A\nC\n",
            )

        assertTrue(diff.contains("--- .gitlab/merge_request_templates/OpenReviewer.md"))
        assertTrue(diff.contains("+++ .gitlab/merge_request_templates/OpenReviewer.md"))
        assertTrue(diff.contains("-B"))
        assertTrue(diff.contains("+C"))
    }
}
