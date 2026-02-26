package com.example.open.reviewer.settings

enum class AiProvider {
    OPENAI,
    GOOGLE,
    COHERE,
    HUGGINGFACE,
    CUSTOM,
}

data class OpenReviewerSettingsState(
    var provider: AiProvider = AiProvider.OPENAI,
    var apiKey: String = "",
    var model: String = "",
    var endpoint: String = "",
    var includeCodeContext: Boolean = false,
)
