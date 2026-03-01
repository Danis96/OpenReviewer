package com.example.open.reviewer.commitchecklist.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GitHubPrTemplateDetectorTest {
    @Test
    fun `detect marks template present when pull_request_template exists`() {
        val root = Files.createTempDirectory("github-template-detect")
        try {
            val template = root.resolve(".github").resolve("pull_request_template.md")
            Files.createDirectories(template.parent)
            Files.writeString(template, "template")

            val result = GitHubPrTemplateDetector.detect(root)

            assertEquals(template.normalize(), result.expectedPath)
            assertEquals(template.normalize(), result.foundPath)
            assertTrue(result.isPresent)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `detect marks template missing when file does not exist`() {
        val root = Files.createTempDirectory("github-template-detect")
        try {
            val result = GitHubPrTemplateDetector.detect(root)

            assertEquals(root.resolve(".github").resolve("pull_request_template.md"), result.expectedPath)
            assertNull(result.foundPath)
            assertTrue(!result.isPresent)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
