package com.example.open.reviewer.architecture.analysis

import com.example.open.reviewer.ai.AiClient
import com.example.open.reviewer.ai.AiClientService
import com.example.open.reviewer.ai.AiSuggestionContext
import com.example.open.reviewer.settings.AiProvider
import com.example.open.reviewer.settings.OpenReviewerSettingsService
import com.example.open.reviewer.settings.OpenReviewerSettingsState
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class ArchitectureAiRequest(
    val repoAggregate: RepoAggregatePayload,
    val preliminaryTopPattern: String?,
    val preliminaryTopConfidence: Double,
    val fileDigests: List<ArchitectureAiFileDigest> = emptyList(),
)

data class ArchitectureAiFileDigest(
    val path: String,
    val kind: String,
    val signals: List<String>,
    val headline: String,
    val keyPoints: List<String>,
)

data class ArchitectureAiClientConfig(
    val requestTimeout: Duration = Duration.ofSeconds(30),
    val maxRetries: Int = 2,
    val initialBackoffMillis: Long = 300,
)

interface ArchitectureAiGateway {
    fun analyze(
        prompt: String,
        settings: OpenReviewerSettingsState,
    ): String
}

class OpenReviewerArchitectureAiGateway(
    private val aiClient: AiClient = AiClientService(),
) : ArchitectureAiGateway {
    override fun analyze(
        prompt: String,
        settings: OpenReviewerSettingsState,
    ): String {
        val context =
            AiSuggestionContext(
                platform = "ARCHITECTURE",
                entryPointSummary = "Architecture aggregate",
                findings = emptyList(),
                prompt = prompt,
            )
        return aiClient.generateSuggestions(context, settings)
    }
}

class ArchitectureAiAnalysisClient(
    private val config: ArchitectureAiClientConfig = ArchitectureAiClientConfig(),
    private val settingsProvider: () -> OpenReviewerSettingsState? = {
        runCatching { OpenReviewerSettingsService.getInstance().state.copy() }.getOrNull()
    },
    private val gateway: ArchitectureAiGateway = OpenReviewerArchitectureAiGateway(),
    private val responseParser: ArchitectureAiResponseParser = ArchitectureAiResponseParser(),
    private val patternNormalizer: PatternNormalizer = PatternNormalizer(),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun analyze(
        request: ArchitectureAiRequest,
        checkCanceled: () -> Unit,
    ): ArchitectureAiStageResult {
        checkCanceled()
        val settings = settingsProvider() ?: return ArchitectureAiStageResult(false, "AI disabled: settings unavailable.")
        if (settings.apiKey.isBlank()) return ArchitectureAiStageResult(false, "AI disabled: missing API key.")
        if (settings.provider == AiProvider.CUSTOM && settings.endpoint.isBlank()) {
            return ArchitectureAiStageResult(false, "AI disabled: missing CUSTOM endpoint.")
        }

        val model = resolveModel(settings)
        val prompt = buildPrompt(request, model)
        val maxAttempts = (config.maxRetries + 1).coerceAtLeast(1)
        var lastError: Throwable? = null

        for (attempt in 1..maxAttempts) {
            checkCanceled()
            try {
                val response = callWithTimeout(prompt, settings, checkCanceled)
                if (response.isBlank()) throw IllegalStateException("AI response was empty.")
                val parsed = responseParser.parse(response)
                if (!parsed.parsed || parsed.response == null) {
                    return ArchitectureAiStageResult(
                        used = false,
                        parsed = false,
                        summary = "AI returned invalid JSON: ${parsed.errors.joinToString("; ")}",
                        normalizedPatterns = emptyList(),
                        graphHints = ArchitectureGraphHints(),
                        rawResponse = response,
                    )
                }
                val normalizedPatterns = patternNormalizer.normalize(parsed.response.patterns)
                return ArchitectureAiStageResult(
                    used = true,
                    parsed = true,
                    summary =
                        "provider=${settings.provider} model=$model attempts=$attempt/$maxAttempts " +
                            "schemaVersion=${parsed.response.schemaVersion} " +
                            "confidence=${"%.2f".format(parsed.response.confidence)} " +
                            "architectureGuess=${parsed.response.architectureGuess}",
                    normalizedPatterns = normalizedPatterns,
                    graphHints = parsed.response.graphHints,
                    rawResponse = response,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                lastError = error
                if (attempt >= maxAttempts || !isRetryable(error)) {
                    break
                }
                cancellableSleep(backoffMillis(attempt), checkCanceled)
            }
        }

        val reason = lastError?.message ?: "unknown error"
        return ArchitectureAiStageResult(
            used = false,
            summary = "AI architecture analysis failed after ${config.maxRetries + 1} attempts: $reason",
            parsed = false,
            normalizedPatterns = emptyList(),
            graphHints = ArchitectureGraphHints(),
            rawResponse = null,
        )
    }

    private fun callWithTimeout(
        prompt: String,
        settings: OpenReviewerSettingsState,
        checkCanceled: () -> Unit,
    ): String {
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<String> { gateway.analyze(prompt, settings) }
        try {
            var remaining = config.requestTimeout.toMillis().coerceAtLeast(1)
            while (true) {
                checkCanceled()
                val slice = remaining.coerceAtMost(75)
                try {
                    return future.get(slice, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    remaining -= slice
                    if (remaining <= 0) {
                        future.cancel(true)
                        throw TimeoutException("request timeout after ${config.requestTimeout.toMillis()}ms")
                    }
                }
            }
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            throw TimeoutException("request timeout after ${config.requestTimeout.toMillis()}ms")
        } catch (cancel: CancellationException) {
            future.cancel(true)
            throw cancel
        } catch (interrupted: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw CancellationException("AI request interrupted/cancelled")
        } finally {
            executor.shutdownNow()
        }
    }

    private fun isRetryable(error: Throwable): Boolean {
        if (error is TimeoutException || error is IOException) return true
        val message = error.message.orEmpty()
        val status = Regex("""status\s+(\d{3})""").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (status == 429) return true
        if (status != null && status >= 500) return true
        return false
    }

    private fun backoffMillis(attempt: Int): Long {
        val factor = 1 shl (attempt - 1).coerceAtMost(6)
        return config.initialBackoffMillis * factor
    }

    private fun cancellableSleep(
        totalMillis: Long,
        checkCanceled: () -> Unit,
    ) {
        var remaining = totalMillis.coerceAtLeast(0)
        while (remaining > 0) {
            checkCanceled()
            val slice = remaining.coerceAtMost(75)
            sleeper(slice)
            remaining -= slice
        }
    }

    private fun resolveModel(settings: OpenReviewerSettingsState): String {
        if (settings.model.isNotBlank()) return settings.model
        return when (settings.provider) {
            AiProvider.OPENAI -> "gpt-4o-mini"
            AiProvider.GOOGLE -> "gemma-3-27b-it"
            AiProvider.COHERE -> "command-r"
            AiProvider.HUGGINGFACE -> "openai/gpt-oss-120b:fastest"
            AiProvider.CUSTOM -> "gpt-4o-mini"
        }
    }

    private fun buildPrompt(
        request: ArchitectureAiRequest,
        model: String,
    ): String {
        val aggregate = request.repoAggregate
        val pattern = request.preliminaryTopPattern ?: "UNKNOWN"
        return buildString {
            appendLine("You are analyzing a repository architecture summary.")
            appendLine("Model: $model")
            appendLine(
                "Goal: infer the most likely architecture style(s), evaluate implementation maturity, " +
                    "highlight highest-impact strengths/risks, and propose concrete, ordered next improvements " +
                    "that are directly justified by the provided repository evidence.",
            )
            appendLine(
                "Focus on practical engineering impact: layering quality, dependency direction, " +
                    "state management consistency, testability, modular boundaries, and maintainability risks.",
            )
            appendLine("Use only the evidence below.")
            appendLine()
            appendLine("Return STRICT JSON only (no markdown, no prose before/after).")
            appendLine("Schema:")
            appendLine("{")
            appendLine("  \"schemaVersion\": 1,")
            appendLine("  \"architectureGuess\": \"string\",")
            appendLine("  \"confidence\": 0.0,")
            appendLine("  \"patterns\": [")
            appendLine("    {")
            appendLine("      \"name\": \"string\",")
            appendLine("      \"confidence\": 0.0,")
            appendLine("      \"evidencePaths\": [\"relative/path.ext\"]")
            appendLine("    }")
            appendLine("  ]")
            appendLine("  \"graphHints\": {")
            appendLine("    \"focusPaths\": [\"relative/path.ext\"],")
            appendLine("    \"focusEdges\": [")
            appendLine("      {\"source\":\"relative/path.ext\",\"target\":\"relative/path.ext\",\"weight\":0.0,\"reason\":\"string\"}")
            appendLine("    ]")
            appendLine("  }")
            appendLine("}")
            appendLine("Rules: confidence values must be [0,1]. evidencePaths must be repo-relative paths.")
            appendLine("graphHints is optional but recommended.")
            appendLine("focusPaths should only include architecture-defining files (UI/STATE/REPO/DI/DATA/config roots).")
            appendLine("focusEdges should express architecture flow edges, not random utility imports.")
            appendLine()
            appendLine("Evidence:")
            appendLine("- preliminary_pattern: $pattern")
            appendLine("- preliminary_confidence: ${"%.2f".format(request.preliminaryTopConfidence)}")
            appendLine("- signal_counts:")
            appendLine(aggregate.signalCounts.joinToString(separator = "\n") { "  - $it" }.ifBlank { "  - none" })
            appendLine("- representative_files:")
            appendLine(aggregate.representativeFiles.joinToString(separator = "\n") { "  - $it" }.ifBlank { "  - none" })
            appendLine("- folder_hints:")
            appendLine(aggregate.folderStructureHints.joinToString(separator = "\n") { "  - $it" }.ifBlank { "  - none" })
            appendLine("- trimmed_edges:")
            appendLine(aggregate.trimmedEdgeGraph.joinToString(separator = "\n") { "  - $it" }.ifBlank { "  - none" })
            appendLine("- file_digests:")
            if (request.fileDigests.isEmpty()) {
                appendLine("  - none")
            } else {
                request.fileDigests.take(MAX_PROMPT_FILE_DIGESTS).forEach { digest ->
                    val signals = digest.signals.joinToString("|").ifBlank { "none" }
                    appendLine("  - ${digest.path} | kind=${digest.kind} | signals=$signals")
                    appendLine("    summary: ${digest.headline.ifBlank { "n/a" }}")
                    if (digest.keyPoints.isNotEmpty()) {
                        digest.keyPoints.take(3).forEach { point ->
                            appendLine("    - $point")
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_PROMPT_FILE_DIGESTS = 50
    }
}
