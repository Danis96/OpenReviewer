package com.example.open.reviewer.architecture.analysis

import java.util.Locale

class PatternNormalizer {
    fun normalize(input: List<ArchitectureDetectedPattern>): List<ArchitectureDetectedPattern> {
        if (input.isEmpty()) return emptyList()
        val grouped = linkedMapOf<String, MutableList<ArchitectureDetectedPattern>>()
        input.forEach { pattern ->
            val canonical = canonicalName(pattern.name)
            grouped.getOrPut(canonical) { mutableListOf() } += pattern
        }
        return grouped.entries
            .map { (canonical, variants) ->
                ArchitectureDetectedPattern(
                    name = canonical,
                    confidence = variants.maxOf { it.confidence },
                    evidencePaths = variants.flatMap { it.evidencePaths }.distinct().sorted(),
                )
            }
            .sortedByDescending { it.confidence }
    }

    private fun canonicalName(raw: String): String {
        val cleaned =
            raw
                .trim()
                .lowercase(Locale.ROOT)
                .replace("_", " ")
                .replace("-", " ")
                .replace(Regex("""\s+"""), " ")

        return when {
            cleaned in setOf("mvvm", "model view viewmodel", "model-view-viewmodel", "model view view model") -> "MVVM"
            cleaned.contains("mvvm") -> "MVVM"
            cleaned.contains("clean") && cleaned.contains("architecture") -> "Clean Architecture"
            cleaned == "clean arch" -> "Clean Architecture"
            cleaned.contains("riverpod") -> "Flutter Riverpod"
            cleaned.contains("bloc") || cleaned.contains("cubit") -> "Flutter BLoC"
            cleaned.contains("provider") -> "Flutter Provider"
            else -> cleaned.split(' ').joinToString(" ") { token -> token.replaceFirstChar { c -> c.uppercase() } }
        }
    }
}
