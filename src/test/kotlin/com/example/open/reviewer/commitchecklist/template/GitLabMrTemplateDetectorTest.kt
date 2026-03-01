package com.example.open.reviewer.commitchecklist.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GitLabMrTemplateDetectorTest {
    @Test
    fun `detect lists markdown templates under gitlab merge_request_templates`() {
        val root = Files.createTempDirectory("gitlab-template-detect")
        try {
            val dir = root.resolve(".gitlab").resolve("merge_request_templates")
            Files.createDirectories(dir)
            val openReviewer = dir.resolve("OpenReviewer.md")
            val defaultTemplate = dir.resolve("default.md")
            Files.writeString(openReviewer, "a")
            Files.writeString(defaultTemplate, "b")
            Files.writeString(dir.resolve("ignore.txt"), "c")

            val result = GitLabMrTemplateDetector.detect(root)

            assertEquals(dir, result.templatesDir)
            assertEquals(
                setOf(defaultTemplate, openReviewer),
                result.foundTemplates.toSet(),
            )
            assertTrue(!result.isMissing)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detect marks missing when templates directory does not exist`() {
        val root = Files.createTempDirectory("gitlab-template-detect")
        try {
            val result = GitLabMrTemplateDetector.detect(root)

            assertTrue(result.foundTemplates.isEmpty())
            assertTrue(result.isMissing)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
