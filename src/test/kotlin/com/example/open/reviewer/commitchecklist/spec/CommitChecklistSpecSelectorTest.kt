package com.example.open.reviewer.commitchecklist.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class CommitChecklistSpecSelectorTest {
    @Test
    fun `preferred openreviewer path wins when both files exist`() {
        val root = Files.createTempDirectory("commit-checklist-spec-test")
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
    fun `fallback root path is used when preferred path is missing`() {
        val root = Files.createTempDirectory("commit-checklist-spec-test")
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
    fun `returns null when spec file is missing in canonical locations`() {
        val root = Files.createTempDirectory("commit-checklist-spec-test")
        try {
            val selected = CommitChecklistSpecSelector.selectExistingSpecPath(root)

            assertNull(selected)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
