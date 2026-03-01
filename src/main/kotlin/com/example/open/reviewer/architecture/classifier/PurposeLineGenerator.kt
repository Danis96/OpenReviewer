package com.example.open.reviewer.architecture.classifier

import java.nio.file.Path
import java.util.Locale

class PurposeLineGenerator {
    fun generate(
        kind: FileKind,
        path: String,
    ): String {
        val subject = subjectFromPath(path)
        val template =
            when (kind) {
                FileKind.UI -> "UI screen/widget for %s."
                FileKind.STATE -> "State holder for %s."
                FileKind.REPOSITORY -> "Data access for %s."
                FileKind.SERVICE -> "Service logic for %s."
                FileKind.DATA_SOURCE -> "Data source for %s."
                FileKind.MODEL -> "Data model for %s."
                FileKind.DI -> "Dependency injection wiring for %s."
                FileKind.CONFIG -> "Configuration for %s."
                FileKind.TEST -> "Test coverage for %s."
                FileKind.OTHER -> "General code for %s."
            }
        val line = template.format(subject)
        return if (line.length <= MAX_LENGTH) line else line.take(MAX_LENGTH - 1) + "…"
    }

    private fun subjectFromPath(path: String): String {
        val fileName = Path.of(path).fileName?.toString().orEmpty()
        val base = fileName.substringBeforeLast('.').ifBlank { "this area" }
        val normalized =
            base
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { "this area" }
        return normalized.lowercase(Locale.ROOT)
    }

    companion object {
        private const val MAX_LENGTH = 300
    }
}
