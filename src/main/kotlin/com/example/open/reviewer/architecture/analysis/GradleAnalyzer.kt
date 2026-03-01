package com.example.open.reviewer.architecture.analysis

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

data class GradleFileFacts(
    val path: String,
    val plugins: List<String>,
    val dependencies: List<String>,
    val modules: List<String>,
)

data class GradleAnalysisResult(
    val files: List<GradleFileFacts>,
    val analyzedFiles: Int,
    val unreadableFiles: Int,
    val plugins: List<String>,
    val dependencies: List<String>,
    val modules: List<String>,
    val androidStackSignals: List<String>,
) {
    fun hasAndroidStack(): Boolean = androidStackSignals.isNotEmpty()
}

class GradleAnalyzer {
    fun analyze(gradlePaths: List<String>): GradleAnalysisResult {
        val files = mutableListOf<GradleFileFacts>()
        var analyzed = 0
        var unreadable = 0

        gradlePaths.sorted().forEach { rawPath ->
            val path = Path.of(rawPath)
            if (!Files.isRegularFile(path)) {
                unreadable += 1
                return@forEach
            }
            val text = runCatching { Files.readString(path) }.getOrNull()
            if (text == null) {
                unreadable += 1
                return@forEach
            }
            analyzed += 1
            files +=
                GradleFileFacts(
                    path = normalizePath(path),
                    plugins = extractPlugins(text),
                    dependencies = extractDependencies(text),
                    modules = extractModules(text),
                )
        }

        val plugins = files.flatMap { it.plugins }.distinct().sorted()
        val dependencies = files.flatMap { it.dependencies }.distinct().sorted()
        val modules = files.flatMap { it.modules }.distinct().sorted()

        val stack = mutableSetOf<String>()
        val p = plugins.joinToString(" ").lowercase(Locale.ROOT)
        val d = dependencies.joinToString(" ").lowercase(Locale.ROOT)
        if ("com.android.application" in p || "com.android.library" in p) stack += "ANDROID_GRADLE_PLUGIN"
        if ("org.jetbrains.kotlin.android" in p || "kotlin-android" in p) stack += "KOTLIN_ANDROID"
        if ("hilt" in p || "hilt" in d || "dagger" in d) stack += "HILT"
        if ("androidx.room" in d || ":room-" in d || "room-ktx" in d) stack += "ROOM"
        if ("retrofit" in d || "okhttp" in d) stack += "RETROFIT"
        if ("compose" in p || "androidx.compose" in d) stack += "COMPOSE"

        return GradleAnalysisResult(
            files = files.sortedBy { it.path },
            analyzedFiles = analyzed,
            unreadableFiles = unreadable,
            plugins = plugins,
            dependencies = dependencies,
            modules = modules,
            androidStackSignals = stack.toList().sorted(),
        )
    }

    private fun extractPlugins(text: String): List<String> {
        val out = mutableSetOf<String>()
        Regex("""id\s*\(\s*["']([^"']+)["']\s*\)""").findAll(text).forEach { out += it.groupValues[1] }
        Regex("""id\s+["']([^"']+)["']""").findAll(text).forEach { out += it.groupValues[1] }
        Regex("""apply\s+plugin:\s*["']([^"']+)["']""").findAll(text).forEach { out += it.groupValues[1] }
        return out.toList().sorted()
    }

    private fun extractDependencies(text: String): List<String> {
        val out = mutableSetOf<String>()
        Regex("""(?:implementation|api|kapt|ksp|classpath|compileOnly|runtimeOnly|testImplementation|androidTestImplementation)\s*\(?\s*["']([^"']+)["']""")
            .findAll(text)
            .forEach { out += it.groupValues[1] }
        return out.toList().sorted()
    }

    private fun extractModules(text: String): List<String> {
        val out = mutableSetOf<String>()
        Regex("""include\s*\(([^)]*)\)""")
            .findAll(text)
            .forEach { match ->
                Regex("""["'](:[^"']+)["']""")
                    .findAll(match.groupValues[1])
                    .forEach { out += it.groupValues[1] }
            }
        Regex("""include\s+(.+)""")
            .findAll(text)
            .forEach { lineMatch ->
                Regex("""["'](:[^"']+)["']""")
                    .findAll(lineMatch.groupValues[1])
                    .forEach { out += it.groupValues[1] }
            }
        Regex("""project\(\s*["'](:[^"']+)["']\s*\)""").findAll(text).forEach { out += it.groupValues[1] }
        return out.toList().sorted()
    }

    private fun normalizePath(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/')
}
