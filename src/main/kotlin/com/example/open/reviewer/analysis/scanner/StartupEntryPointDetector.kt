package com.example.open.reviewer.analysis.scanner

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

data class DiscoveredEntryPoint(
    val name: String,
    val file: VirtualFile,
    val line: Int? = null,
)

class StartupEntryPointDetector {
    private val ignoredDirectories =
        setOf(
            ".git",
            ".idea",
            ".gradle",
            ".dart_tool",
            "build",
            "out",
            "node_modules",
            ".next",
            "test",
            "tests",
            "androidTest",
            "integration_test",
            "__tests__",
        )

    fun detect(
        project: Project,
        indicator: ProgressIndicator,
    ): List<DiscoveredEntryPoint> {
        val root =
            project.basePath
                ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                ?: return emptyList()

        val candidates = collectStartupCandidateFiles(root, indicator)
        val entryPoints = mutableListOf<DiscoveredEntryPoint>()

        candidates.forEachIndexed { index, file ->
            indicator.checkCanceled()
            indicator.text2 = "Detecting startup entry points (${index + 1}/${candidates.size})"

            val content = readText(file) ?: return@forEachIndexed
            findEntryInFile(file, content)?.let { entryPoints.addAll(it) }
        }

        return entryPoints.distinctBy { Triple(it.file.path, it.name, it.line) }
    }

    private fun collectStartupCandidateFiles(
        root: VirtualFile,
        indicator: ProgressIndicator,
    ): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        var scanned = 0
        val scanLimit = 6000

        VfsUtilCore.iterateChildrenRecursively(
            root,
            { file ->
                indicator.checkCanceled()
                if (file.isDirectory) {
                    !file.name.startsWith(".") && file.name !in ignoredDirectories
                } else {
                    true
                }
            },
            { file ->
                if (!file.isDirectory) {
                    scanned += 1
                    if (isLikelyStartupFile(file)) {
                        files.add(file)
                    }
                }
                scanned < scanLimit
            },
        )

        return files.sortedByDescending { startupPriority(it) }
    }

    private fun isLikelyStartupFile(file: VirtualFile): Boolean {
        if (isTestLikePath(file.path)) return false
        val name = file.name.lowercase()
        if (name in setOf("main.dart", "main.kt", "main.java", "appdelegate.swift", "application.kt")) return true
        if (name == "application.java") return true
        if (name.matches(Regex("main(_[a-z0-9_]+)?\\.dart"))) return true
        if (name.contains("mainactivity") || name.contains("startup") || name.contains("application")) return true
        return false
    }

    private fun findEntryInFile(
        file: VirtualFile,
        content: String,
    ): List<DiscoveredEntryPoint>? {
        val matches = mutableListOf<DiscoveredEntryPoint>()

        findFirst(content, "\\bfun\\s+main\\s*\\(", "main", file)?.let { matches.add(it) }
        findFirst(content, "\\bfun\\s+onCreate\\s*\\(", "onCreate", file)?.let { matches.add(it) }
        findFirst(content, "\\b(?:Future\\s*<\\s*void\\s*>\\s+)?void\\s+main\\s*\\(", "main", file)?.let { matches.add(it) }
        findFirst(content, "\\bFuture\\s*<\\s*void\\s*>\\s+main\\s*\\(", "main", file)?.let { matches.add(it) }
        findFirst(content, "\\bvoid\\s+onCreate\\s*\\(", "onCreate", file)?.let { matches.add(it) }
        findFirst(content, "\\bfunc\\s+application\\s*\\(", "application", file)?.let { matches.add(it) }
        findFirst(content, "\\bfunc\\s+didFinishLaunching\\s*\\(", "didFinishLaunching", file)?.let { matches.add(it) }

        return matches.ifEmpty { null }
    }

    private fun startupPriority(file: VirtualFile): Int {
        val name = file.name.lowercase()
        return when {
            name == "main.dart" -> 100
            name.matches(Regex("main(_[a-z0-9_]+)?\\.dart")) -> 95
            name == "main.kt" || name == "main.java" -> 90
            name.contains("mainactivity") -> 85
            name.contains("application") -> 80
            name.contains("startup") -> 75
            else -> 10
        }
    }

    private fun isTestLikePath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        if ("/test/" in normalized || "/tests/" in normalized || "/integration_test/" in normalized) return true
        if ("/src/test/" in normalized || "/src/androidtest/" in normalized) return true
        if (normalized.endsWith("_test.dart") || normalized.endsWith("test.dart")) return true
        if (normalized.endsWith("test.kt") || normalized.endsWith("test.java") || normalized.endsWith("test.swift")) return true
        return false
    }

    private fun findFirst(
        content: String,
        pattern: String,
        name: String,
        file: VirtualFile,
    ): DiscoveredEntryPoint? {
        val regex = Regex(pattern)
        val match = regex.find(content) ?: return null
        val line = content.take(match.range.first).count { it == '\n' } + 1
        return DiscoveredEntryPoint(name = name, file = file, line = line)
    }

    private fun readText(file: VirtualFile): String? {
        if (file.length > 600_000) return null
        return try {
            VfsUtilCore.loadText(file)
        } catch (_: IOException) {
            null
        }
    }
}
