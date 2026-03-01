package com.example.open.reviewer.commitchecklist.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class SpecDetectionAcceptanceTest {
    @Test
    fun `preferred path wins over fallback when both exist`() {
        val root = Files.createTempDirectory("spec-detection-acceptance")
        try {
            val preferred = root.resolve(".openreviewer").resolve("COMMIT_CHECKLIST.md")
            val fallback = root.resolve("COMMIT_CHECKLIST.md")
            Files.createDirectories(preferred.parent)
            Files.writeString(preferred, "preferred")
            Files.writeString(fallback, "fallback")

            val selected = CommitChecklistSpecSelector.selectExistingSpecPath(root)

            assertEquals(preferred.normalize(), selected)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fallback path is selected when preferred is missing`() {
        val root = Files.createTempDirectory("spec-detection-acceptance")
        try {
            val fallback = root.resolve("COMMIT_CHECKLIST.md")
            Files.writeString(fallback, "fallback")

            val selected = CommitChecklistSpecSelector.selectExistingSpecPath(root)

            assertEquals(fallback.normalize(), selected)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing spec returns null`() {
        val root = Files.createTempDirectory("spec-detection-acceptance")
        try {
            val selected = CommitChecklistSpecSelector.selectExistingSpecPath(root)

            assertNull(selected)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
