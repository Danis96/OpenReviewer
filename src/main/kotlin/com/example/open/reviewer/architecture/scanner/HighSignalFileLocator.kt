package com.example.open.reviewer.architecture.scanner

import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name

data class HighSignalScanConfig(
    val topViewModelLimit: Int = 8,
    val topDiModuleLimit: Int = 8,
    val topFlutterStateFileLimit: Int = 8,
    val topFlutterFeatureFolderLimit: Int = 8,
    val ignoredDirectoryNames: Set<String> =
        setOf(
            ".git",
            ".idea",
            ".gradle",
            ".dart_tool",
            "build",
            "out",
            "node_modules",
            ".next",
            "dist",
            "coverage",
            "test",
            "tests",
            "androidTest",
            "integration_test",
            "__tests__",
        ),
    val ignoredPathSegments: Set<String> =
        setOf(
            "/build/",
            "/.gradle/",
            "/node_modules/",
            "/.dart_tool/",
            "/.idea/",
        ),
    val diModuleReadLimitBytes: Long = 256_000L,
)

data class HighSignalScanResult(
    val androidManifestFiles: List<String>,
    val gradleBuildFiles: List<String>,
    val settingsGradleFiles: List<String>,
    val applicationFiles: List<String>,
    val topViewModelFiles: List<String>,
    val topDiModuleFiles: List<String>,
    val flutterMainFiles: List<String>,
    val pubspecFiles: List<String>,
    val topFlutterFeatureFolders: List<String>,
    val topFlutterStateFiles: List<String>,
    val scannedFileCount: Int,
    val elapsedMs: Long,
) {
    fun highSignalFiles(): List<String> {
        return listOf(
            androidManifestFiles,
            gradleBuildFiles,
            settingsGradleFiles,
            applicationFiles,
            topViewModelFiles,
            topDiModuleFiles,
            flutterMainFiles,
            pubspecFiles,
            topFlutterStateFiles,
        ).flatten().distinct().sorted()
    }
}

class HighSignalFileLocator(
    private val config: HighSignalScanConfig = HighSignalScanConfig(),
) {
    fun locate(project: Project): HighSignalScanResult {
        val basePath = project.basePath ?: return emptyResult(0L)
        return locate(Path.of(basePath))
    }

    fun locate(root: Path): HighSignalScanResult {
        if (!root.isDirectory()) return emptyResult(0L)
        val started = System.nanoTime()

        val androidManifestFiles = mutableListOf<String>()
        val gradleBuildFiles = mutableListOf<String>()
        val settingsGradleFiles = mutableListOf<String>()
        val applicationFiles = mutableListOf<String>()
        val viewModels = mutableListOf<ScoredPath>()
        val diModules = mutableListOf<ScoredPath>()
        val flutterMainFiles = mutableListOf<String>()
        val pubspecFiles = mutableListOf<String>()
        val flutterStateFiles = mutableListOf<ScoredPath>()
        val featureFolderScores = mutableMapOf<String, Int>()

        var scannedFileCount = 0

        Files.walkFileTree(root, scanVisitor(root) { file ->
            scannedFileCount += 1
            val normalizedPath = normalizePath(file)
            val name = file.name
            val lowerName = name.lowercase()
            val pathLower = normalizedPath.lowercase()

            when {
                lowerName == "androidmanifest.xml" -> androidManifestFiles += normalizedPath
                lowerName == "build.gradle" || lowerName == "build.gradle.kts" -> gradleBuildFiles += normalizedPath
                lowerName == "settings.gradle" || lowerName == "settings.gradle.kts" -> settingsGradleFiles += normalizedPath
                isFlutterEntrypointFile(lowerName) -> flutterMainFiles += normalizedPath
                lowerName == "pubspec.yaml" -> pubspecFiles += normalizedPath
            }

            if (isApplicationFile(lowerName)) {
                applicationFiles += normalizedPath
            }
            if (isViewModelFile(lowerName)) {
                viewModels += ScoredPath(normalizedPath, scoreViewModel(pathLower))
            }
            if (isDiModuleCandidate(lowerName) && fileLooksLikeDiModule(file)) {
                diModules += ScoredPath(normalizedPath, scoreDiModule(pathLower))
            }
            if (isFlutterStateFile(lowerName)) {
                flutterStateFiles += ScoredPath(normalizedPath, scoreFlutterStateFile(pathLower))
            }

            val featureFolder = extractTopLevelFlutterFeatureFolder(pathLower)
            if (featureFolder != null) {
                featureFolderScores[featureFolder] = (featureFolderScores[featureFolder] ?: 0) + 1
            }
        })

        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        return HighSignalScanResult(
            androidManifestFiles = androidManifestFiles.sorted(),
            gradleBuildFiles = gradleBuildFiles.sorted(),
            settingsGradleFiles = settingsGradleFiles.sorted(),
            applicationFiles = applicationFiles.sorted(),
            topViewModelFiles = topScored(viewModels, config.topViewModelLimit),
            topDiModuleFiles = topScored(diModules, config.topDiModuleLimit),
            flutterMainFiles = flutterMainFiles.sorted(),
            pubspecFiles = pubspecFiles.sorted(),
            topFlutterFeatureFolders = topFeatureFolders(featureFolderScores, config.topFlutterFeatureFolderLimit),
            topFlutterStateFiles = topScored(flutterStateFiles, config.topFlutterStateFileLimit),
            scannedFileCount = scannedFileCount,
            elapsedMs = elapsedMs,
        )
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
                if (directoryName in config.ignoredDirectoryNames) return FileVisitResult.SKIP_SUBTREE
                if (config.ignoredPathSegments.any { normalized.contains(it.lowercase()) }) {
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

    private fun fileLooksLikeDiModule(file: Path): Boolean {
        val size = runCatching { file.fileSize() }.getOrDefault(Long.MAX_VALUE)
        if (size > config.diModuleReadLimitBytes) return false
        val content =
            runCatching { Files.readString(file, StandardCharsets.UTF_8) }
                .getOrElse { return false }
        return DI_ANNOTATION_REGEX.containsMatchIn(content)
    }

    private fun extractTopLevelFlutterFeatureFolder(pathLower: String): String? {
        val marker = "/lib/"
        val markerIndex = pathLower.indexOf(marker)
        if (markerIndex < 0) return null
        val afterLib = pathLower.substring(markerIndex + marker.length)
        if (afterLib.isBlank()) return null
        val segments = afterLib.split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return null

        val first = segments[0]
        val second = segments[1]
        if (first in setOf("feature", "features", "module", "modules")) {
            if (second.contains('.')) return null
            return "$first/$second"
        }
        if (first in NON_FEATURE_FLUTTER_DIRS || first.contains('.')) return null
        return first
    }

    private fun topScored(
        values: List<ScoredPath>,
        limit: Int,
    ): List<String> {
        if (limit <= 0) return emptyList()
        return values
            .distinctBy { it.path }
            .sortedWith(compareByDescending<ScoredPath> { it.score }.thenBy { it.path })
            .take(limit)
            .map { it.path }
    }

    private fun topFeatureFolders(
        folderScores: Map<String, Int>,
        limit: Int,
    ): List<String> {
        if (limit <= 0) return emptyList()
        return folderScores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }
    }

    private fun scoreViewModel(pathLower: String): Int {
        var score = 100
        if ("/presentation/" in pathLower) score += 20
        if ("/ui/" in pathLower) score += 15
        if ("/feature/" in pathLower || "/features/" in pathLower) score += 15
        if ("/android/" in pathLower) score += 10
        return score
    }

    private fun scoreDiModule(pathLower: String): Int {
        var score = 100
        if ("/di/" in pathLower) score += 25
        if ("/inject/" in pathLower || "/injection/" in pathLower) score += 20
        if ("/module/" in pathLower || "/modules/" in pathLower) score += 10
        return score
    }

    private fun scoreFlutterStateFile(pathLower: String): Int {
        var score = 100
        if ("/lib/" in pathLower) score += 10
        if ("/feature/" in pathLower || "/features/" in pathLower) score += 20
        if ("/presentation/" in pathLower || "/ui/" in pathLower) score += 10
        return score
    }

    private fun isApplicationFile(lowerName: String): Boolean {
        return lowerName.endsWith("application.kt") || lowerName.endsWith("application.java")
    }

    private fun isViewModelFile(lowerName: String): Boolean = lowerName.endsWith("viewmodel.kt")

    private fun isDiModuleCandidate(lowerName: String): Boolean {
        return lowerName.endsWith("module.kt") || lowerName.endsWith("module.java")
    }

    private fun isFlutterStateFile(lowerName: String): Boolean {
        if (!lowerName.endsWith(".dart")) return false
        return lowerName.contains("provider") || lowerName.contains("bloc") || lowerName.contains("notifier")
    }

    private fun isFlutterEntrypointFile(lowerName: String): Boolean {
        return FLUTTER_MAIN_FILE_REGEX.matches(lowerName)
    }

    private fun normalizePath(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/')

    private fun emptyResult(elapsedMs: Long): HighSignalScanResult {
        return HighSignalScanResult(
            androidManifestFiles = emptyList(),
            gradleBuildFiles = emptyList(),
            settingsGradleFiles = emptyList(),
            applicationFiles = emptyList(),
            topViewModelFiles = emptyList(),
            topDiModuleFiles = emptyList(),
            flutterMainFiles = emptyList(),
            pubspecFiles = emptyList(),
            topFlutterFeatureFolders = emptyList(),
            topFlutterStateFiles = emptyList(),
            scannedFileCount = 0,
            elapsedMs = elapsedMs,
        )
    }

    private data class ScoredPath(
        val path: String,
        val score: Int,
    )

    companion object {
        private val DI_ANNOTATION_REGEX = Regex("(^|\\s)@Module(\\s|\\(|$)")
        private val FLUTTER_MAIN_FILE_REGEX = Regex("^main(?:[_-][a-z0-9_]+)*\\.dart$")

        private val NON_FEATURE_FLUTTER_DIRS =
            setOf(
                "core",
                "shared",
                "common",
                "widgets",
                "theme",
                "l10n",
                "generated",
                "gen",
                "assets",
            )
    }
}
