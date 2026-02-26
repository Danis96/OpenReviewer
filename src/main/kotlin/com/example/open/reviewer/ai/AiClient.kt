package com.example.open.reviewer.ai

import com.example.open.reviewer.settings.AiProvider
import com.example.open.reviewer.settings.OpenReviewerSettingsState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface AiClient {
    fun ping(settings: OpenReviewerSettingsState): AiPingResult

    fun generateSuggestions(
        context: AiSuggestionContext,
        settings: OpenReviewerSettingsState,
    ): String

    fun listModels(settings: OpenReviewerSettingsState): List<String>
}

data class AiPingResult(
    val success: Boolean,
    val message: String,
)

@Service(Service.Level.APP)
class AiClientService : AiClient {
    private val pingClient: AiClient = HttpAiClient()
    private val suggestionClient: AiClient = HttpAiClient()

    override fun ping(settings: OpenReviewerSettingsState): AiPingResult = pingClient.ping(settings)

    override fun generateSuggestions(
        context: AiSuggestionContext,
        settings: OpenReviewerSettingsState,
    ): String {
        return suggestionClient.generateSuggestions(context, settings)
    }

    override fun listModels(settings: OpenReviewerSettingsState): List<String> {
        return suggestionClient.listModels(settings)
    }
}

class HttpAiClient : AiClient {
    private val logger = Logger.getInstance(HttpAiClient::class.java)
    private val openAiDefaultModel = "gpt-4o-mini"
    private val googleDefaultModel = "gemma-3-27b-it"
    private val cohereDefaultModel = "command-r"
    private val huggingFaceDefaultModel = "openai/gpt-oss-120b:fastest"
    private val customDefaultModel = "gpt-4o-mini"

    private val httpClient: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build()

    override fun ping(settings: OpenReviewerSettingsState): AiPingResult {
        if (settings.apiKey.isBlank()) {
            return AiPingResult(false, "API key is required.")
        }

        val resolvedModel = resolveModel(settings)
        val request = buildPingRequest(settings) ?: return AiPingResult(false, "Unsupported provider configuration.")
        logInfo("AI ping start provider=${settings.provider} model=$resolvedModel endpoint=${request.uri()}")

        return runCatching {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() !in 200..299) {
                logWarn("AI ping failed provider=${settings.provider} model=$resolvedModel status=${response.statusCode()}")
                return@runCatching AiPingResult(false, "Connection failed with status ${response.statusCode()}.")
            }

            if (settings.provider == AiProvider.HUGGINGFACE) {
                val modelProbe = buildHuggingFaceProbeRequest(resolvedModel, settings.apiKey)
                logInfo("AI ping probe start provider=${settings.provider} model=$resolvedModel endpoint=${modelProbe.uri()}")
                val probeResponse =
                    httpClient.send(modelProbe, HttpResponse.BodyHandlers.ofString())
                if (probeResponse.statusCode() !in 200..299) {
                    val snippet = probeResponse.body().lineSequence().joinToString(" ").take(220)
                    logWarn(
                        "AI ping probe failed provider=${settings.provider} " +
                            "model=$resolvedModel status=${probeResponse.statusCode()} body=$snippet",
                    )
                    return@runCatching AiPingResult(
                        false,
                        "Connected to Hugging Face, but model \"$resolvedModel\" " +
                            "failed with status ${probeResponse.statusCode()}. $snippet",
                    )
                }
            }

            logInfo("AI ping success provider=${settings.provider} model=$resolvedModel")
            AiPingResult(true, "Connection successful. Model: $resolvedModel")
        }.getOrElse { error ->
            logWarn("AI ping error provider=${settings.provider} model=$resolvedModel error=${error.message}", error)
            AiPingResult(false, error.message ?: "Connection check failed.")
        }
    }

    override fun generateSuggestions(
        context: AiSuggestionContext,
        settings: OpenReviewerSettingsState,
    ): String {
        if (settings.apiKey.isBlank()) {
            throw IllegalArgumentException("API key is required.")
        }

        if (settings.provider == AiProvider.CUSTOM && settings.endpoint.isBlank()) {
            throw IllegalArgumentException("Endpoint is required for CUSTOM provider.")
        }

        val request =
            buildSuggestionsRequest(context, settings)
                ?: throw IllegalArgumentException("Unsupported provider configuration.")
        val model = resolveModel(settings)
        logInfo(
            "AI suggestions request start provider=${settings.provider}" +
                " model=$model endpoint=${request.uri()} promptLength=${context.prompt.length}",
        )
        logInfo("AI suggestions prompt begin")
        logInfo(context.prompt)
        logInfo("AI suggestions prompt end")
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            val bodySnippet = response.body().lineSequence().joinToString(" ").take(240)
            logWarn(
                "AI suggestions request failed provider=${settings.provider}" +
                    " model=$model status=${response.statusCode()} body=$bodySnippet",
            )
            throw IllegalStateException("Suggestion request failed with status ${response.statusCode()}. $bodySnippet")
        }

        val suggestionText = extractSuggestionText(settings.provider, response.body())
        if (suggestionText.isBlank()) {
            logWarn(
                "AI suggestions request empty provider=${settings.provider} " +
                    "model=$model status=${response.statusCode()}",
            )
            throw IllegalStateException("AI provider returned an empty suggestions payload.")
        }
        logInfo(
            "AI suggestions request success provider=${settings.provider} " +
                "model=$model status=${response.statusCode()} outputLength=${suggestionText.length}",
        )
        return suggestionText
    }

    override fun listModels(settings: OpenReviewerSettingsState): List<String> {
        if (settings.apiKey.isBlank()) {
            return emptyList()
        }

        val request = buildListModelsRequest(settings) ?: return emptyList()
        logInfo("AI list models start provider=${settings.provider} endpoint=${request.uri()}")
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            logWarn("AI list models failed provider=${settings.provider} status=${response.statusCode()}")
            return emptyList()
        }

        val parsed = extractModelIds(response.body())
        logInfo("AI list models success provider=${settings.provider} count=${parsed.size}")
        return parsed.distinct().sorted()
    }

    private fun buildPingRequest(settings: OpenReviewerSettingsState): HttpRequest? {
        val endpoint =
            when (settings.provider) {
                AiProvider.OPENAI -> "https://api.openai.com/v1/models"
                AiProvider.GOOGLE -> "https://generativelanguage.googleapis.com/v1beta/openai/models"
                AiProvider.COHERE -> "https://api.cohere.com/v1/models"
                AiProvider.HUGGINGFACE -> "https://huggingface.co/api/whoami-v2"
                AiProvider.CUSTOM -> settings.endpoint.trim().takeIf { it.isNotBlank() }
            } ?: return null

        val builder =
            HttpRequest.newBuilder()
                .uri(URI(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer ${settings.apiKey}")
                .GET()

        if (settings.provider == AiProvider.COHERE) {
            builder.header("Cohere-Version", "2022-12-06")
        }

        return builder.build()
    }

    private fun buildListModelsRequest(settings: OpenReviewerSettingsState): HttpRequest? {
        val endpoint =
            when (settings.provider) {
                AiProvider.OPENAI -> "https://api.openai.com/v1/models"
                AiProvider.GOOGLE -> "https://generativelanguage.googleapis.com/v1beta/openai/models"
                AiProvider.COHERE -> "https://api.cohere.com/v1/models"
                AiProvider.HUGGINGFACE -> "https://router.huggingface.co/v1/models"
                AiProvider.CUSTOM -> resolveCustomModelsEndpoint(settings.endpoint)
            } ?: return null

        val builder =
            HttpRequest.newBuilder()
                .uri(URI(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer ${settings.apiKey}")
                .GET()

        if (settings.provider == AiProvider.COHERE) {
            builder.header("Cohere-Version", "2022-12-06")
        }

        return builder.build()
    }

    private fun buildSuggestionsRequest(
        context: AiSuggestionContext,
        settings: OpenReviewerSettingsState,
    ): HttpRequest? {
        return when (settings.provider) {
            AiProvider.OPENAI ->
                buildOpenAiSuggestionRequest(
                    endpoint = "https://api.openai.com/v1/chat/completions",
                    model = settings.model.ifBlank { openAiDefaultModel },
                    prompt = context.prompt,
                    apiKey = settings.apiKey,
                )
            AiProvider.GOOGLE ->
                buildOpenAiSuggestionRequest(
                    endpoint = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                    model = settings.model.ifBlank { googleDefaultModel },
                    prompt = context.prompt,
                    apiKey = settings.apiKey,
                )

            AiProvider.COHERE ->
                buildCohereSuggestionRequest(
                    model = settings.model.ifBlank { cohereDefaultModel },
                    prompt = context.prompt,
                    apiKey = settings.apiKey,
                )

            AiProvider.HUGGINGFACE -> {
                buildHuggingFaceSuggestionRequest(
                    model = settings.model.ifBlank { huggingFaceDefaultModel },
                    prompt = context.prompt,
                    apiKey = settings.apiKey,
                )
            }

            AiProvider.CUSTOM ->
                buildOpenAiSuggestionRequest(
                    endpoint = resolveCustomChatEndpoint(settings.endpoint),
                    model = settings.model.ifBlank { customDefaultModel },
                    prompt = context.prompt,
                    apiKey = settings.apiKey,
                )
        }
    }

    private fun buildOpenAiSuggestionRequest(
        endpoint: String,
        model: String,
        prompt: String,
        apiKey: String,
    ): HttpRequest {
        val body =
            """
            {
              "model": "${jsonEscape(model)}",
              "messages": [
                {
                  "role": "user",
                  "content": "${jsonEscape(prompt)}"
                }
              ],
              "temperature": 0.2
            }
            """.trimIndent()

        return HttpRequest.newBuilder()
            .uri(URI(endpoint))
            .timeout(Duration.ofSeconds(25))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    private fun buildCohereSuggestionRequest(
        model: String,
        prompt: String,
        apiKey: String,
    ): HttpRequest {
        val body =
            """
            {
              "model": "${jsonEscape(model)}",
              "prompt": "${jsonEscape(prompt)}",
              "temperature": 0.2,
              "max_tokens": 550
            }
            """.trimIndent()

        return HttpRequest.newBuilder()
            .uri(URI("https://api.cohere.com/v1/generate"))
            .timeout(Duration.ofSeconds(25))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Cohere-Version", "2022-12-06")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    private fun buildHuggingFaceSuggestionRequest(
        model: String,
        prompt: String,
        apiKey: String,
    ): HttpRequest {
        val body =
            """
            {
              "model": "${jsonEscape(model)}",
              "messages": [
                {
                  "role": "user",
                  "content": "${jsonEscape(prompt)}"
                }
              ],
              "temperature": 0.2,
              "max_tokens": 550
            }
            """.trimIndent()

        return HttpRequest.newBuilder()
            .uri(URI("https://router.huggingface.co/v1/chat/completions"))
            .timeout(Duration.ofSeconds(45))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    private fun buildHuggingFaceProbeRequest(
        model: String,
        apiKey: String,
    ): HttpRequest {
        val body =
            """
            {
              "model": "${jsonEscape(model)}",
              "messages": [
                {
                  "role": "user",
                  "content": "Respond with OK"
                }
              ],
              "max_tokens": 8,
              "temperature": 0
            }
            """.trimIndent()
        return HttpRequest.newBuilder()
            .uri(URI("https://router.huggingface.co/v1/chat/completions"))
            .timeout(Duration.ofSeconds(25))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    private fun resolveCustomChatEndpoint(rawEndpoint: String): String {
        val trimmed = rawEndpoint.trim().trimEnd('/')
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed
        }
        return "$trimmed/chat/completions"
    }

    private fun resolveCustomModelsEndpoint(rawEndpoint: String): String? {
        val trimmed = rawEndpoint.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return null
        return when {
            trimmed.endsWith("/chat/completions") -> "${trimmed.removeSuffix("/chat/completions")}/models"
            trimmed.endsWith("/v1") -> "$trimmed/models"
            else -> "$trimmed/models"
        }
    }

    private fun extractSuggestionText(
        provider: AiProvider,
        body: String,
    ): String {
        val escapedText =
            when (provider) {
                AiProvider.OPENAI, AiProvider.GOOGLE, AiProvider.CUSTOM -> {
                    Regex(""""content"\s*:\s*"((?:\\.|[^"\\])*)"""")
                        .find(body)
                        ?.groupValues
                        ?.get(1)
                }

                AiProvider.COHERE -> {
                    Regex(""""generations"\s*:\s*\[\s*\{[^}]*"text"\s*:\s*"((?:\\.|[^"\\])*)"""", setOf(RegexOption.DOT_MATCHES_ALL))
                        .find(body)
                        ?.groupValues
                        ?.get(1)
                        ?: Regex(""""text"\s*:\s*"((?:\\.|[^"\\])*)"""")
                            .find(body)
                            ?.groupValues
                            ?.get(1)
                }

                AiProvider.HUGGINGFACE -> {
                    Regex(""""content"\s*:\s*"((?:\\.|[^"\\])*)"""")
                        .find(body)
                        ?.groupValues
                        ?.get(1)
                }
            } ?: return ""

        return jsonUnescape(escapedText).trim()
    }

    private fun jsonEscape(value: String): String {
        return buildString(value.length + 16) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }

    private fun jsonUnescape(value: String): String {
        return value
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun extractModelIds(body: String): List<String> {
        val idMatches =
            Regex(""""id"\s*:\s*"([^"]+)"""")
                .findAll(body)
                .map { it.groupValues[1] }
                .toList()
        if (idMatches.isNotEmpty()) return idMatches

        return Regex(""""name"\s*:\s*"([^"]+)"""")
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun resolveModel(settings: OpenReviewerSettingsState): String {
        return when (settings.provider) {
            AiProvider.OPENAI -> settings.model.ifBlank { openAiDefaultModel }
            AiProvider.GOOGLE -> settings.model.ifBlank { googleDefaultModel }
            AiProvider.COHERE -> settings.model.ifBlank { cohereDefaultModel }
            AiProvider.HUGGINGFACE -> settings.model.ifBlank { huggingFaceDefaultModel }
            AiProvider.CUSTOM -> settings.model.ifBlank { customDefaultModel }
        }
    }

    private fun logInfo(message: String) {
        logger.info(message)
        println("[OpenReviewer][AiClient][INFO] $message")
    }

    private fun logWarn(message: String) {
        logger.warn(message)
        System.err.println("[OpenReviewer][AiClient][WARN] $message")
    }

    private fun logWarn(
        message: String,
        error: Throwable,
    ) {
        logger.warn(message, error)
        System.err.println("[OpenReviewer][AiClient][WARN] $message")
        error.printStackTrace(System.err)
    }
}

class StubAiClient : AiClient {
    override fun ping(settings: OpenReviewerSettingsState): AiPingResult {
        if (settings.apiKey.isBlank()) {
            return AiPingResult(false, "API key is required.")
        }

        if (settings.provider == AiProvider.CUSTOM && settings.endpoint.isBlank()) {
            return AiPingResult(false, "Endpoint is required for CUSTOM provider.")
        }

        return AiPingResult(true, "Connection successful (stub).")
    }

    override fun generateSuggestions(
        context: AiSuggestionContext,
        settings: OpenReviewerSettingsState,
    ): String {
        val hasCritical = context.findings.any { it.severity.name == "CRITICAL" }
        val topTitle = context.findings.firstOrNull()?.title ?: "startup findings"
        val primaryImpact = if (hasCritical) "HIGH" else "MEDIUM"

        return """
Title: Remove blocking startup calls
Impact: $primaryImpact
Reasoning: Prioritize eliminating blocking operations found near entry points to improve startup responsiveness.

Title: Defer non-essential initialization
Impact: HIGH
Reasoning: Move network/database setup related to $topTitle behind first render to reduce startup latency.

Title: Add startup task budget
Impact: MEDIUM
Reasoning: Track critical-path tasks and keep only must-have work in onCreate/main for predictable startup performance.
""".trim()
    }

    override fun listModels(settings: OpenReviewerSettingsState): List<String> {
        return emptyList()
    }
}
