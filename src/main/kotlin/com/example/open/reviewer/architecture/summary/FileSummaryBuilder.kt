package com.example.open.reviewer.architecture.summary

import com.example.open.reviewer.architecture.model.FileFacts
import com.example.open.reviewer.architecture.model.FileSummary
import java.nio.file.Path
import java.util.Locale

class FileSummaryBuilder {
    fun build(
        path: String,
        facts: FileFacts,
        content: String,
    ): FileSummary {
        val normalizedPath = normalize(path)
        val lowerPath = normalizedPath.lowercase(Locale.ROOT)
        val lower = content.lowercase(Locale.ROOT)
        val subject = subjectFromPath(normalizedPath)
        val headline = cap("Role: ${roleFor(lowerPath, lower)} | Subject: $subject", MAX_HEADLINE_CHARS)

        val points = mutableListOf<String>()
        points += cap("Stats: L${facts.lineCount} C${facts.classCount} F${facts.functionCount} I${facts.importCount}.", MAX_POINT_CHARS)

        val signals = facts.signalTags.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct().sorted()
        if (signals.isNotEmpty()) {
            points += cap("Signals: ${signals.take(4).joinToString(", ")}.", MAX_POINT_CHARS)
        }

        val stack = detectStack(lowerPath, lower)
        if (stack.isNotEmpty()) {
            points += cap("Stack: ${stack.joinToString(", ")}.", MAX_POINT_CHARS)
        }

        if (points.size < MAX_POINTS) {
            val entryHints = detectEntrypoints(lowerPath, lower)
            if (entryHints.isNotEmpty()) {
                points += cap("Entrypoints: ${entryHints.joinToString(", ")}.", MAX_POINT_CHARS)
            }
        }

        return FileSummary(
            headline = headline,
            keyPoints = points.take(MAX_POINTS).toMutableList(),
        )
    }

    private fun roleFor(
        lowerPath: String,
        lowerContent: String,
    ): String {
        return when {
            isTest(lowerPath, lowerContent) -> "Test"
            isDi(lowerPath, lowerContent) -> "Dependency Injection"
            isState(lowerPath, lowerContent) -> "State"
            isRepository(lowerPath, lowerContent) -> "Repository"
            isUi(lowerPath, lowerContent) -> "UI"
            isService(lowerPath, lowerContent) -> "Service"
            isModel(lowerPath, lowerContent) -> "Model"
            isConfig(lowerPath) -> "Configuration"
            else -> "General"
        }
    }

    private fun detectStack(
        lowerPath: String,
        lower: String,
    ): List<String> {
        val stack = mutableListOf<String>()
        if (hasAny(lowerPath, lower, "hilt", "@module", "@inject", "@provides", "@hiltandroidapp")) stack += "hilt-di"
        if (hasAny(lowerPath, lower, "@entity", "@dao", "@database", "room")) stack += "room"
        if (hasAny(lowerPath, lower, "@get", "@post", "@put", "@delete", "retrofit")) stack += "retrofit"
        if (hasAny(lowerPath, lower, "provider", "changenotifier")) stack += "provider"
        if (hasAny(lowerPath, lower, "flutter_bloc", " bloc", "cubit")) stack += "bloc"
        if (hasAny(lowerPath, lower, "riverpod", "providerscope", "statenotifier")) stack += "riverpod"
        if (hasAny(lowerPath, lower, "getit", "get_it")) stack += "getit"
        if (hasAny(lowerPath, lower, "@freezed", "freezed")) stack += "freezed"
        if (hasAny(lowerPath, lower, "@jsonserializable", "json_serializable")) stack += "json-serializable"
        return stack.distinct().sorted()
    }

    private fun detectEntrypoints(
        lowerPath: String,
        lower: String,
    ): List<String> {
        val points = mutableListOf<String>()
        if ("androidmanifest.xml" in lowerPath) points += "manifest"
        if (Regex("""\b(?:future<\s*void\s*>\s+|void\s+)main\s*\(""").containsMatchIn(lower)) points += "main()"
        if ("runapp(" in lower) points += "runApp()"
        if ("@composable" in lower) points += "compose"
        if (Regex("""class\s+\w+\s*:\s*\w*activity""").containsMatchIn(lower) || " extends activity" in lower) points += "activity"
        return points.distinct().sorted()
    }

    private fun subjectFromPath(path: String): String {
        val fileName = Path.of(path).fileName?.toString().orEmpty().substringBeforeLast('.')
        if (fileName.isBlank()) return "unknown"
        return fileName
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
            .ifBlank { "unknown" }
    }

    private fun normalize(path: String): String = Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/')

    private fun hasAny(
        lowerPath: String,
        lowerContent: String,
        vararg hints: String,
    ): Boolean = hints.any { hint -> hint in lowerPath || hint in lowerContent }

    private fun isTest(
        path: String,
        content: String,
    ): Boolean = hasAny(path, content, "/test/", "/tests/", "test.kt", "test.dart", "@test", "junit", "flutter_test")

    private fun isDi(
        path: String,
        content: String,
    ): Boolean = hasAny(path, content, "/di/", "module", "component", "@module", "@inject", "@provides", "@hiltandroidapp")

    private fun isState(
        path: String,
        content: String,
    ): Boolean = hasAny(path, content, "viewmodel", "state", "bloc", "cubit", "provider", "riverpod", "changenotifier")

    private fun isRepository(
        path: String,
        content: String,
    ): Boolean = hasAny(path, content, "repository")

    private fun isUi(
        path: String,
        content: String,
    ): Boolean = hasAny(path, content, "/ui/", "widget", "@composable", "activity", "fragment", "screen", "page")

    private fun isService(
        path: String,
        content: String,
    ): Boolean = hasAny(path, content, "/service/", "service", "usecase", "interactor")

    private fun isModel(
        path: String,
        content: String,
    ): Boolean = hasAny(path, content, "/model/", "dto", "entity", "schema", "@freezed", "@jsonserializable")

    private fun isConfig(path: String): Boolean = hasAny(path, path, "androidmanifest.xml", "build.gradle", "settings.gradle", "/config/")

    private fun cap(
        text: String,
        max: Int,
    ): String = if (text.length <= max) text else text.take(max - 1) + "…"

    companion object {
        private const val MAX_HEADLINE_CHARS = 120
        private const val MAX_POINT_CHARS = 100
        private const val MAX_POINTS = 3
    }
}
