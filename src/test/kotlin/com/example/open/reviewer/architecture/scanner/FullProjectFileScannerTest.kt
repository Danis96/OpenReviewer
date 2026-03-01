package com.example.open.reviewer.architecture.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class FullProjectFileScannerTest {
    @Test
    fun `collects supported files and excludes configured directories`() {
        withTempRepo { root ->
            write(root, "lib/main.dart", "void main() {}")
            write(root, "app/src/main/java/com/acme/MainActivity.kt", "class MainActivity")
            write(root, "app/src/main/java/com/acme/Legacy.java", "class Legacy {}")
            write(root, "app/src/main/AndroidManifest.xml", "<manifest/>")
            write(root, "build.gradle.kts", "plugins {}")
            write(root, "settings.gradle.kts", "rootProject.name = \"demo\"")

            write(root, "build/generated/Ignore.kt", "class Ignore")
            write(root, ".gradle/tmp/Ignore.java", "class Ignore {}")
            write(root, ".idea/Ignore.dart", "void ignore() {}")
            write(root, ".dart_tool/Ignore.dart", "void ignore() {}")
            write(root, "node_modules/pkg/Ignore.kt", "class Ignore")

            val result = FullProjectFileScanner().scan(root)

            assertTrue(result.dartFiles.any { it.endsWith("/lib/main.dart") })
            assertTrue(result.kotlinFiles.any { it.endsWith("/MainActivity.kt") })
            assertTrue(result.javaFiles.any { it.endsWith("/Legacy.java") })
            assertTrue(result.manifestFiles.any { it.endsWith("/AndroidManifest.xml") })
            assertTrue(result.gradleFiles.any { it.endsWith("/build.gradle.kts") })
            assertTrue(result.gradleFiles.any { it.endsWith("/settings.gradle.kts") })

            val all = result.allFiles()
            assertFalse(all.any { it.contains("/build/") && it.endsWith("/Ignore.kt") })
            assertFalse(all.any { it.contains("/.gradle/") })
            assertFalse(all.any { it.contains("/.idea/") })
            assertFalse(all.any { it.contains("/.dart_tool/") })
            assertFalse(all.any { it.contains("/node_modules/") })
        }
    }

    @Test
    fun `supports cancellation and progress callbacks`() {
        withTempRepo { root ->
            repeat(300) { index ->
                write(root, "lib/file_$index.dart", "void f$index() {}")
            }
            val progressCalls = AtomicInteger(0)
            val scanner =
                FullProjectFileScanner(
                    FullProjectScanConfig(progressUpdateEveryFiles = 50),
                )

            var cancelled = false
            try {
                scanner.scan(
                    root = root,
                    progress =
                        object : FullProjectScanProgress {
                            override fun checkCanceled() {
                                if (progressCalls.get() >= 3) {
                                    throw CancellationException("cancel")
                                }
                            }

                            override fun onProgress(
                                scannedFiles: Int,
                                collectedFiles: Int,
                                currentPath: String,
                            ) {
                                progressCalls.incrementAndGet()
                            }
                        },
                )
            } catch (_: CancellationException) {
                cancelled = true
            }

            assertTrue(cancelled)
            assertTrue(progressCalls.get() >= 3)
        }
    }

    private fun withTempRepo(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("full-project-file-scanner-test")
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
    ) {
        val file = root.resolve(relativePath)
        file.parent?.createDirectories()
        file.writeText(content)
    }
}
