package com.example.open.reviewer.architecture.scanner

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory

data class FullProjectScanConfig(
    val excludedDirectoryNames: Set<String> =
        setOf(
            "build",
            ".gradle",
            ".idea",
            ".dart_tool",
            "node_modules",
        ),
    val excludedPathSegments: Set<String> =
        setOf(
            "/build/",
            "/.gradle/",
            "/.idea/",
            "/.dart_tool/",
            "/node_modules/",
        ),
    val progressUpdateEveryFiles: Int = 200,
)

data class FullProjectScanResult(
    val rootPath: String,
    val dartFiles: List<String>,
    val kotlinFiles: List<String>,
    val javaFiles: List<String>,
    val manifestFiles: List<String>,
    val gradleFiles: List<String>,
    val scannedFileCount: Int,
    val collectedFileCount: Int,
    val elapsedMs: Long,
) {
    fun allFiles(): List<String> {
        return listOf(
            dartFiles,
            kotlinFiles,
            javaFiles,
            manifestFiles,
            gradleFiles,
        ).flatten().distinct().sorted()
    }
}

interface FullProjectScanProgress {
    fun checkCanceled()

    fun onProgress(
        scannedFiles: Int,
        collectedFiles: Int,
        currentPath: String,
    )
}

class FullProjectFileScanner(
    private val config: FullProjectScanConfig = FullProjectScanConfig(),
) {
    fun scan(project: Project, indicator: ProgressIndicator): FullProjectScanResult {
        val basePath = project.basePath ?: return emptyResult("")
        indicator.text = "Deep architecture scan"
        indicator.isIndeterminate = false
        return scan(Path.of(basePath), indicator)
    }

    fun scan(
        root: Path,
        indicator: ProgressIndicator,
    ): FullProjectScanResult {
        indicator.text = "Deep architecture scan"
        indicator.isIndeterminate = false
        return scan(
            root = root,
            progress =
                object : FullProjectScanProgress {
                    override fun checkCanceled() {
                        indicator.checkCanceled()
                    }

                    override fun onProgress(
                        scannedFiles: Int,
                        collectedFiles: Int,
                        currentPath: String,
                    ) {
                        indicator.text2 = "Scanned $scannedFiles files • matched $collectedFiles"
                        indicator.fraction = (scannedFiles / PROGRESS_SOFT_TARGET.toDouble()).coerceIn(0.0, 0.98)
                    }
                },
        )
    }

    fun scan(
        root: Path,
        progress: FullProjectScanProgress = NoOpProgress,
    ): FullProjectScanResult {
        if (!root.isDirectory()) return emptyResult(root.toString())

        val started = System.nanoTime()
        val dartFiles = mutableListOf<String>()
        val kotlinFiles = mutableListOf<String>()
        val javaFiles = mutableListOf<String>()
        val manifestFiles = mutableListOf<String>()
        val gradleFiles = mutableListOf<String>()
        var scannedFileCount = 0

        Files.walkFileTree(root, scanVisitor(root) { file ->
            progress.checkCanceled()
            scannedFileCount += 1
            val normalized = normalizePath(file)
            val lowerName = file.fileName.toString().lowercase()

            when {
                lowerName.endsWith(".dart") -> dartFiles += normalized
                lowerName.endsWith(".kt") -> kotlinFiles += normalized
                lowerName.endsWith(".java") -> javaFiles += normalized
                lowerName == "androidmanifest.xml" -> manifestFiles += normalized
            }

            if (lowerName in GRADLE_FILE_NAMES) {
                gradleFiles += normalized
            }

            if (scannedFileCount % config.progressUpdateEveryFiles == 0) {
                progress.onProgress(
                    scannedFiles = scannedFileCount,
                    collectedFiles = dartFiles.size + kotlinFiles.size + javaFiles.size + manifestFiles.size + gradleFiles.size,
                    currentPath = normalized,
                )
            }
        })

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val result =
            FullProjectScanResult(
                rootPath = normalizePath(root),
                dartFiles = dartFiles.sorted(),
                kotlinFiles = kotlinFiles.sorted(),
                javaFiles = javaFiles.sorted(),
                manifestFiles = manifestFiles.sorted(),
                gradleFiles = gradleFiles.sorted(),
                scannedFileCount = scannedFileCount,
                collectedFileCount = dartFiles.size + kotlinFiles.size + javaFiles.size + manifestFiles.size + gradleFiles.size,
                elapsedMs = elapsedMs,
            )
        progress.onProgress(
            scannedFiles = result.scannedFileCount,
            collectedFiles = result.collectedFileCount,
            currentPath = normalizePath(root),
        )
        return result
    }

    private fun scanVisitor(
        root: Path,
        onFile: (Path) -> Unit,
    ): FileVisitor<Path> {
        return object : FileVisitor<Path> {
            override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (dir == root) return FileVisitResult.CONTINUE
                val directoryName = dir.fileName?.toString().orEmpty()
                val normalized = normalizePath(dir).lowercase()
                if (directoryName in config.excludedDirectoryNames) return FileVisitResult.SKIP_SUBTREE
                if (config.excludedPathSegments.any { normalized.contains(it.lowercase()) }) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                onFile(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                exc: IOException,
            ): FileVisitResult = FileVisitResult.CONTINUE

            override fun postVisitDirectory(
                dir: Path,
                exc: IOException?,
            ): FileVisitResult = FileVisitResult.CONTINUE
        }
    }

    private fun normalizePath(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/')

    private fun emptyResult(root: String): FullProjectScanResult {
        return FullProjectScanResult(
            rootPath = root,
            dartFiles = emptyList(),
            kotlinFiles = emptyList(),
            javaFiles = emptyList(),
            manifestFiles = emptyList(),
            gradleFiles = emptyList(),
            scannedFileCount = 0,
            collectedFileCount = 0,
            elapsedMs = 0L,
        )
    }

    private object NoOpProgress : FullProjectScanProgress {
        override fun checkCanceled() = Unit

        override fun onProgress(
            scannedFiles: Int,
            collectedFiles: Int,
            currentPath: String,
        ) = Unit
    }

    companion object {
        private val GRADLE_FILE_NAMES =
            setOf(
                "build.gradle",
                "build.gradle.kts",
                "settings.gradle",
                "settings.gradle.kts",
            )
        private const val PROGRESS_SOFT_TARGET = 20_000
    }
}
