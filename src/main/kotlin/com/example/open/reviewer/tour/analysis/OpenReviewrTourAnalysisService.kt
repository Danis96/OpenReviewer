package com.example.open.reviewer.tour.analysis

import com.example.open.reviewer.ai.AiClientService
import com.example.open.reviewer.ai.AiSuggestionContext
import com.example.open.reviewer.analysis.Finding
import com.example.open.reviewer.settings.AiProvider
import com.example.open.reviewer.settings.OpenReviewerSettingsService
import com.example.open.reviewer.tour.model.MobilePlatform
import com.example.open.reviewer.tour.model.OpenReviewrTourStop
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class OpenReviewrTourAnalysisService(
    project: Project,
) {
    private val settingsService = OpenReviewerSettingsService.getInstance()
    private val aiClient = ApplicationManager.getApplication().getService(AiClientService::class.java)
    private val contextBuilder = OpenReviewrTourContextBuilder()

    fun analyzeStop(
        stop: OpenReviewrTourStop,
        indicator: ProgressIndicator,
    ): OpenReviewrTourAnalysisResult {
        val settings = settingsService.state
        if (settings.apiKey.isBlank()) {
            return OpenReviewrTourAnalysisResult(
                stop = stop,
                summary = null,
                error = "Missing API key. Configure it in Open Reviewer settings.",
            )
        }
        if (settings.provider == AiProvider.CUSTOM && settings.endpoint.isBlank()) {
            return OpenReviewrTourAnalysisResult(
                stop = stop,
                summary = null,
                error = "Missing CUSTOM endpoint. Configure it in Open Reviewer settings.",
            )
        }
        if (isGeneratedOrVendorFile(stop.filePath)) {
            return OpenReviewrTourAnalysisResult(
                stop = stop,
                summary = null,
                error = "Skipping generated/vendor file for analysis.",
            )
        }

        indicator.checkCanceled()
        indicator.text2 = "Preparing context for ${stop.filePath.substringAfterLast('/')}"
        val codeContext = contextBuilder.buildContext(stop)
        if (codeContext.isBlank()) {
            return OpenReviewrTourAnalysisResult(
                stop = stop,
                summary = null,
                error = "Unable to read source file context.",
            )
        }

        val prompt = buildPrompt(stop, codeContext)
        val context =
            AiSuggestionContext(
                platform = platformLabel(stop.platform),
                entryPointSummary = stop.description ?: "OpenReviewr tour stop",
                findings = emptyList<Finding>(),
                prompt = prompt,
            )

        indicator.checkCanceled()
        indicator.text2 = "Calling AI for ${stop.filePath.substringAfterLast('/')}"
        val rawResponse =
            runCatching {
                aiClient.generateSuggestions(context, settings)
            }.getOrElse {
                return OpenReviewrTourAnalysisResult(
                    stop = stop,
                    summary = null,
                    error = "AI request failed: ${it.message ?: "unknown error"}",
                )
            }

        val parsed = parseSummary(rawResponse)
        if (parsed == null) {
            return OpenReviewrTourAnalysisResult(
                stop = stop,
                summary = null,
                error = "AI response parsing failed. Try again with a different model.",
            )
        }

        return OpenReviewrTourAnalysisResult(stop = stop, summary = parsed)
    }

    private fun buildPrompt(
        stop: OpenReviewrTourStop,
        code: String,
    ): String {
        val markerDescription = stop.description ?: ""
        return """
You are a senior mobile software architect.

Explain the purpose of this file clearly for onboarding developers.

Focus on:
- responsibility
- key flows
- important dependencies
- potential risks

Keep the explanation concise but insightful.

Return ONLY valid JSON with this exact schema:
{
  "summary": "...",
  "keyResponsibilities": ["..."],
  "risks": ["..."],
  "relatedFiles": ["..."]
}

Rules:
- Do not include markdown fences.
- If risks or relatedFiles are unknown, return empty arrays.
- Keep keyResponsibilities concise (3 to 6 items).

Input:
{
  "filePath": "${jsonEscape(stop.filePath)}",
  "platform": "${platformLabel(stop.platform)}",
  "markerDescription": "${jsonEscape(markerDescription)}",
  "code": "${jsonEscape(code)}"
}
""".trim()
    }

    private fun parseSummary(response: String): OpenReviewrTourSummary? {
        val sanitized =
            response
                .replace(Regex("(?is)<think>.*?</think>"), "")
                .replace(Regex("(?is)<analysis>.*?</analysis>"), "")
                .replace(Regex("(?is)```(?:json)?"), "")
                .replace("```", "")
                .trim()

        val payload = extractOuterJsonObject(sanitized) ?: return null
        val summary = extractJsonField(payload, "summary")?.takeIf { it.isNotBlank() } ?: return null
        val responsibilities = extractJsonStringArray(payload, "keyResponsibilities")
        if (responsibilities.isEmpty()) return null
        val risks = extractJsonStringArray(payload, "risks").ifEmpty { null }
        val relatedFiles = extractJsonStringArray(payload, "relatedFiles").ifEmpty { null }

        return OpenReviewrTourSummary(
            summary = summary,
            keyResponsibilities = responsibilities,
            risks = risks,
            relatedFiles = relatedFiles,
        )
    }

    private fun extractOuterJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (index in start until text.length) {
            val ch = text[index]
            if (inString) {
                if (escape) {
                    escape = false
                } else if (ch == '\\') {
                    escape = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun extractJsonField(
        block: String,
        field: String,
    ): String? {
        val doubleQuoted =
            Regex("""(?is)\"$field\"\s*:\s*\"((?:\\\\.|[^\"\\\\])*)\"""")
                .find(block)
                ?.groupValues
                ?.get(1)
        if (!doubleQuoted.isNullOrBlank()) return jsonUnescape(doubleQuoted).trim()
        return null
    }

    private fun extractJsonStringArray(
        payload: String,
        field: String,
    ): List<String> {
        val rawArray =
            Regex("""(?is)\"$field\"\s*:\s*\[(.*?)]""")
                .find(payload)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                .orEmpty()
        if (rawArray.isBlank()) return emptyList()

        return Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
            .findAll(rawArray)
            .map { match -> jsonUnescape(match.groupValues[1]).trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun platformLabel(platform: MobilePlatform): String {
        return when (platform) {
            MobilePlatform.ANDROID -> "ANDROID"
            MobilePlatform.FLUTTER -> "FLUTTER"
            MobilePlatform.REACT_NATIVE -> "REACT_NATIVE"
            MobilePlatform.IOS -> "IOS"
            MobilePlatform.UNKNOWN -> "UNKNOWN"
        }
    }

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun jsonUnescape(value: String): String {
        return value
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun isGeneratedOrVendorFile(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        return normalized.contains("/build/") ||
            normalized.contains("/generated/") ||
            normalized.contains("/node_modules/") ||
            normalized.contains("/pods/") ||
            normalized.contains("/deriveddata/")
    }

    companion object {
        fun getInstance(project: Project): OpenReviewrTourAnalysisService {
            return project.getService(OpenReviewrTourAnalysisService::class.java)
        }
    }
}
