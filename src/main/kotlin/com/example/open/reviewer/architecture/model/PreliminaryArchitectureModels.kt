package com.example.open.reviewer.architecture.model

enum class ArchitecturePattern {
    MVVM,
    CLEAN_ARCHITECTURE,
    FLUTTER_PROVIDER,
    FLUTTER_BLOC,
    FLUTTER_RIVERPOD,
}

data class PatternConfidence(
    val pattern: ArchitecturePattern,
    val confidence: Double,
    val score: Int,
    val maxScore: Int,
    val signals: List<String>,
)

data class PreliminaryArchitectureGuess(
    val isPreliminary: Boolean,
    val topPattern: ArchitecturePattern?,
    val topConfidence: Double,
    val patternConfidences: List<PatternConfidence>,
)
