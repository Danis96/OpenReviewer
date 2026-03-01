package com.example.open.reviewer.architecture.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ArchitectureFileCacheServiceTest {
    @Test
    fun `skips unchanged files and analyzes only changed files`() {
        withTempRepo { root ->
            val file = write(root, "lib/main.dart", "void main() {}")
            val service = ArchitectureFileCacheService()

            val first = service.analyzeFiles(listOf(file.toString()))
            val entry = service.getEntry(file.toString())
            val second = service.analyzeFiles(listOf(file.toString()))

            assertEquals(1, first.analyzedCount)
            assertEquals(0, first.skippedCount)
            assertEquals(0, second.analyzedCount)
            assertEquals(1, second.skippedCount)
            assertTrue(entry?.fileSummary?.headline?.startsWith("Role: ") == true)
            assertTrue(entry?.fileSummary?.keyPoints?.isNotEmpty() == true)
        }
    }

    @Test
    fun `cache invalidates when file hash changes`() {
        withTempRepo { root ->
            val file = write(root, "app/src/main/java/com/acme/A.kt", "class A")
            val service = ArchitectureFileCacheService()

            val first = service.analyzeFiles(listOf(file.toString()))
            val firstEntry = service.getEntry(file.toString())
            Thread.sleep(2)
            write(root, "app/src/main/java/com/acme/A.kt", "class A { fun x() = 1 }")
            val second = service.analyzeFiles(listOf(file.toString()))
            val secondEntry = service.getEntry(file.toString())

            assertEquals(1, first.analyzedCount)
            assertEquals(1, second.analyzedCount)
            assertNotNull(firstEntry)
            assertNotNull(secondEntry)
            assertTrue(firstEntry!!.hash != secondEntry!!.hash)
            assertTrue(secondEntry.lastAnalyzedAtMillis >= firstEntry.lastAnalyzedAtMillis)
            assertTrue(secondEntry.fileFacts.functionCount >= 1)
        }
    }

    @Test
    fun `clear cache removes all entries`() {
        withTempRepo { root ->
            val file = write(root, "settings.gradle.kts", "rootProject.name = \"demo\"")
            val service = ArchitectureFileCacheService()

            service.analyzeFiles(listOf(file.toString()))
            assertTrue(service.size() > 0)

            service.clearCache()
            assertEquals(0, service.size())
            assertEquals(0, service.getState().entries.size)
        }
    }

    private fun withTempRepo(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("architecture-file-cache-test")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun write(
        root: Path,
        relativePath: String,
        content: String,
    ): Path {
        val file = root.resolve(relativePath)
        file.parent?.createDirectories()
        file.writeText(content)
        return file
    }
}
