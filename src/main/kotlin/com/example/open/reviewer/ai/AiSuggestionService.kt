package com.example.open.reviewer.ai

import com.example.open.reviewer.analysis.AiRiskAdjustment
import com.example.open.reviewer.analysis.AiRiskParseStatus
import com.example.open.reviewer.analysis.AiRiskResult
import com.example.open.reviewer.analysis.Finding
import com.example.open.reviewer.analysis.Suggestion
import com.example.open.reviewer.analysis.SuggestionImpact
import com.example.open.reviewer.analysis.scanner.DiscoveredEntryPoint
import com.example.open.reviewer.analysis.scanner.StartupEntryPointDetector
import com.example.open.reviewer.settings.AiProvider
import com.example.open.reviewer.settings.OpenReviewerSettingsService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import kotlin.math.roundToInt

@Service(Service.Level.PROJECT)
class AiSuggestionService(private val project: Project) {
    private val logger = Logger.getInstance(AiSuggestionService::class.java)
    private val settingsService = OpenReviewerSettingsService.getInstance()
    private val aiClient = ApplicationManager.getApplication().getService(AiClientService::class.java)
    private val entryPointDetector = StartupEntryPointDetector()

    fun generateSuggestions(
        findings: List<Finding>,
        indicator: ProgressIndicator,
    ): List<Suggestion> {
        val settings = settingsService.state
        logInfo(
            "AI suggestion pipeline start provider=${settings.provider} findings=${findings.size} " +
                "hasApiKey=${settings.apiKey.isNotBlank()} customEndpoint=${settings.endpoint.isNotBlank()}",
        )
        if (shouldUseFallback(settings.provider, settings.apiKey, settings.endpoint)) {
            logInfo("AI suggestion pipeline fallback precheck provider=${settings.provider} reason=missing_configuration")
            return fallbackSuggestions(findings)
        }

        val entryPoints = entryPointDetector.detect(project, indicator)
        logInfo("AI suggestion pipeline detected entryPoints=${entryPoints.size}")
        val context =
            AiSuggestionContext(
                platform = detectPlatform(entryPoints, findings),
                entryPointSummary = summarizeEntryPoints(entryPoints),
                findings = findings.take(5),
                prompt =
                    buildPrompt(
                        findings = findings.take(5),
                        entryPoints = entryPoints,
                        includeCodeContext = settings.includeCodeContext,
                    ),
            )
        logInfo(
            "AI suggestion pipeline context platform=${context.platform} " +
                "topFindings=${context.findings.size} promptLength=${context.prompt.length}",
        )

        val response =
            runCatching {
                aiClient.generateSuggestions(context, settings)
            }.getOrElse {
                logWarn("AI suggestion pipeline client error provider=${settings.provider} error=${it.message}", it)
                return fallbackSuggestions(findings)
            }
        logInfo("AI suggestion pipeline raw response length=${response.length}")
        val parsed = parseSuggestions(response)
        if (parsed.isEmpty()) {
            logWarn("AI suggestion pipeline parse produced 0 suggestions, using fallback")
            return fallbackSuggestions(findings)
        }
        logInfo("AI suggestion pipeline success suggestions=${parsed.size}")
        return parsed
    }

    fun generateRiskAdjustment(
        findings: List<Finding>,
        baseRiskScore: Int,
        indicator: ProgressIndicator,
    ): AiRiskResult {
        val settings = settingsService.state

        if (shouldUseFallback(settings.provider, settings.apiKey, settings.endpoint)) {
            logInfo("AI risk pipeline fallback precheck provider=${settings.provider} reason=missing_configuration")
            return AiRiskResult.fallback(baseRiskScore, AiRiskParseStatus.CONFIG_FALLBACK)
        }

        val entryPoints = entryPointDetector.detect(project, indicator)
        val context =
            AiSuggestionContext(
                platform = detectPlatform(entryPoints, findings),
                entryPointSummary = summarizeEntryPoints(entryPoints),
                findings = findings.take(8),
                prompt =
                    buildRiskPrompt(
                        findings = findings,
                        entryPoints = entryPoints,
                        baseRiskScore = baseRiskScore,
                        includeCodeContext = settings.includeCodeContext,
                    ),
            )

        val response =
            runCatching {
                aiClient.generateSuggestions(context, settings)
            }.getOrElse {
                logWarn("AI risk pipeline client error provider=${settings.provider} error=${it.message}", it)
                return AiRiskResult.fallback(baseRiskScore, AiRiskParseStatus.CLIENT_ERROR)
            }

        val parsed = parseRiskAdjustmentPayload(response, baseRiskScore)
        if (parsed == null) {
            logWarn("AI risk pipeline parse error: empty or invalid response")
            return AiRiskResult.fallback(baseRiskScore, AiRiskParseStatus.PARSE_ERROR)
        }
        return parsed
    }

    private fun shouldUseFallback(
        provider: AiProvider,
        apiKey: String,
        endpoint: String,
    ): Boolean {
        if (apiKey.isBlank()) return true
        if (provider == AiProvider.CUSTOM && endpoint.isBlank()) return true
        return false
    }

    private fun detectPlatform(
        entryPoints: List<DiscoveredEntryPoint>,
        findings: List<Finding>,
    ): String {
        val joined =
            buildString {
                entryPoints.forEach { append(it.file.name).append(' ') }
                findings.forEach { append(it.filePath ?: "").append(' ') }
            }.lowercase(Locale.ROOT)

        return when {
            "dart" in joined || "flutter" in joined -> "Flutter"
            "mainactivity" in joined || ".kt" in joined || ".java" in joined -> "Android"
            else -> "Android"
        }
    }

    private fun summarizeEntryPoints(entryPoints: List<DiscoveredEntryPoint>): String {
        if (entryPoints.isEmpty()) {
            return "No explicit entry point detected; using startup-related files heuristics."
        }

        return entryPoints.take(3).joinToString(separator = "; ") {
            val location =
                buildString {
                    append(it.file.name)
                    it.line?.let { line -> append(':').append(line) }
                }
            "${it.name} in $location"
        }
    }

    private fun buildPrompt(
        findings: List<Finding>,
        entryPoints: List<DiscoveredEntryPoint>,
        includeCodeContext: Boolean,
    ): String {
        val platform = detectPlatform(entryPoints, findings)
        val entryPointSummary = summarizeEntryPoints(entryPoints)
        val codeContexts = if (includeCodeContext) collectCodeContexts(entryPoints, charBudget = 40_000) else emptyList()
        logInfo("AI suggestion pipeline codeContext include=$includeCodeContext snippets=${codeContexts.size}")

        val findingLines =
            findings.mapIndexed { index, finding ->
                val location =
                    buildString {
                        if (!finding.filePath.isNullOrBlank()) {
                            append(" @ ").append(finding.filePath)
                            finding.line?.let { append(':').append(it) }
                        }
                    }
                val snippet = formatSnippet(finding.codeSnippet)
                "${index + 1}. [${finding.severity}] ${finding.title}$location\n   ${finding.description}$snippet"
            }.joinToString("\n")

        val codeContextBlock =
            if (codeContexts.isEmpty()) {
                "Code context: not included."
            } else {
                buildString {
                    append("Code context snippets (bounded):\n")
                    codeContexts.forEachIndexed { index, snippet ->
                        append("--- Snippet ").append(index + 1).append(" ---\n")
                        append(snippet).append('\n')
                    }
                }.trimEnd()
            }

        return """
Platform: $platform
Entry points: $entryPointSummary

Top findings (max 5):
$findingLines

$codeContextBlock

Task:
Provide 3 to 6 actionable startup optimization suggestions.
For each suggestion include title, impact, and reasoning tied to findings.

Important:
- Return ONLY valid JSON.
- Do not include markdown fences, headings, prefaces, XML tags, or any extra text.
- Use this exact schema:
[
  {"title":"...", "impact":"LOW|MEDIUM|HIGH", "reasoning":"..."}
]
""".trim()
    }

    private fun buildRiskPrompt(
        findings: List<Finding>,
        entryPoints: List<DiscoveredEntryPoint>,
        baseRiskScore: Int,
        includeCodeContext: Boolean,
    ): String {
        val platform = detectPlatform(entryPoints, findings)
        val entryPointSummary = summarizeEntryPoints(entryPoints)
        val signals = summarizeRiskSignals(findings)
        val topFindings =
            findings.take(8).mapIndexed { index, finding ->
                val location =
                    buildString {
                        if (!finding.filePath.isNullOrBlank()) {
                            append(" @ ").append(finding.filePath)
                            finding.line?.let { append(':').append(it) }
                        }
                    }
                "${index + 1}. [${finding.severity}] ${finding.title}$location - ${finding.description}"
            }.joinToString("\n")
        val codeContexts = if (includeCodeContext) collectCodeContexts(entryPoints, charBudget = 20_000) else emptyList()
        val codeContextBlock =
            if (codeContexts.isEmpty()) {
                "Code context: not included."
            } else {
                buildString {
                    append("Code context snippets (bounded):\n")
                    codeContexts.forEachIndexed { index, snippet ->
                        append("--- Snippet ").append(index + 1).append(" ---\n")
                        append(snippet).append('\n')
                    }
                }.trimEnd()
            }

        return """
Platform: $platform
Entry points: $entryPointSummary
Deterministic base risk score: $baseRiskScore

Signal summary:
- info_findings: ${signals.infoCount}
- warn_findings: ${signals.warnCount}
- critical_findings: ${signals.criticalCount}
- blocking_signals: ${signals.blockingCount}
- network_signals: ${signals.networkCount}
- synchronous_io_signals: ${signals.syncIoCount}
- heavy_init_signals: ${signals.heavyInitCount}
- duplicate_or_repeated_calls_signals: ${signals.duplicateCallCount}

Top findings:
$topFindings

$codeContextBlock

Task:
Assess whether deterministic startup risk score should be adjusted.
Return risk adjustments only as bounded deltas. Use evidence tied to findings.

Rules:
- Return ONLY valid JSON.
- No markdown fences, no extra keys, no prose outside JSON.
- risk_score MUST be an integer from 0 to 100.
- Overall confidence is 0.0 to 1.0.
- Each adjustment delta MUST be between -10 and 10.
- Keep total intended adjustment conservative.

Use this exact schema:
{
  "risk_score": 0,
  "confidence": 0.0,
  "reason": "...",
  "risk_adjustments": [
    {"factor":"...", "delta":0, "confidence":0.0, "evidence":"..."}
  ]
}
""".trim()
    }

    private fun collectCodeContexts(
        entryPoints: List<DiscoveredEntryPoint>,
        charBudget: Int,
    ): List<String> {
        var remaining = charBudget
        val snippets = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        entryPoints.forEach { entryPoint ->
            if (remaining <= 0) return@forEach
            val path = entryPoint.file.path.takeIf { it.isNotBlank() } ?: return@forEach
            if (!seen.add(path)) return@forEach

            val snippet = extractWholeFile(path)
            if (snippet.isBlank()) return@forEach

            val bounded = snippet.take(remaining)
            snippets.add(bounded)
            remaining -= bounded.length
        }

        return snippets
    }

    private fun extractWholeFile(filePath: String): String {
        return runCatching {
            val path = Paths.get(filePath)
            if (!Files.exists(path) || !Files.isRegularFile(path)) return ""
            val allLines = Files.readAllLines(path, StandardCharsets.UTF_8)
            if (allLines.isEmpty()) return ""

            val header = "File: $filePath (full entry-point file)\n"
            val body =
                allLines.indices.joinToString("\n") { idx ->
                    val linePrefix = (idx + 1).toString().padStart(5, ' ')
                    "$linePrefix | ${allLines[idx]}"
                }
            "$header$body"
        }.getOrDefault("")
    }

    private fun formatSnippet(snippet: String?): String {
        if (snippet.isNullOrBlank()) return ""
        val lines = snippet.lineSequence().take(10).map { it.trim() }.toList()
        if (lines.isEmpty()) return ""
        return "\n   Snippet: ${lines.joinToString(" ").take(240)}"
    }

    private fun parseSuggestions(response: String): List<Suggestion> {
        val sanitized =
            response
                .replace(Regex("(?is)<think>.*?</think>"), "")
                .replace(Regex("(?is)<analysis>.*?</analysis>"), "")
                .replace(Regex("(?is)```(?:json)?"), "")
                .replace("```", "")
                .trim()

        val fromJson = parseJsonLikeSuggestions(sanitized)
        if (fromJson.isNotEmpty()) return fromJson

        val blocks = sanitized.split(Regex("\\n\\s*\\n"))
        val suggestions = mutableListOf<Suggestion>()

        blocks.forEach { block ->
            val title =
                Regex("(?im)^\\s*\\*{0,2}title\\*{0,2}\\s*[:\\-]\\s*(.+)$")
                    .find(block)?.groupValues?.get(1)?.trim()
            val impactText =
                Regex("(?im)^\\s*\\*{0,2}impact\\*{0,2}\\s*[:\\-]\\s*(LOW|MEDIUM|HIGH|Low|Medium|High)$")
                    .find(block)?.groupValues?.get(1)?.trim()?.uppercase(Locale.ROOT)
            val reasoning =
                Regex("(?is)^.*?\\b[Rr]easoning\\s*[:\\-]\\s*(.+)$")
                    .find(block)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")

            if (!title.isNullOrBlank() && !impactText.isNullOrBlank() && !reasoning.isNullOrBlank()) {
                val impact = runCatching { SuggestionImpact.valueOf(impactText) }.getOrNull() ?: SuggestionImpact.MEDIUM
                suggestions.add(Suggestion(title = title, details = normalizeSuggestionText(reasoning), impact = impact))
            }
        }

        if (suggestions.isNotEmpty()) return suggestions

        // Fallback parser: tolerate markdown bullets/headers and multiline reasoning.
        val normalized =
            sanitized
                .replace(Regex("(?m)^\\s*[-*]\\s*"), "")
                .replace(Regex("(?m)^\\s*#+\\s*"), "")
        val pattern =
            Regex(
                "(?is)Title\\s*[:\\-]\\s*(.+?)\\s+Impact\\s*[:\\-]\\s*(LOW|MEDIUM|HIGH|Low|Medium|High)\\s+" +
                    "Reasoning\\s*[:\\-]\\s*(.+?)(?=\\s+Title\\s*[:\\-]|\\z)",
            )
        pattern.findAll(normalized).forEach { match ->
            val title = match.groupValues[1].trim()
            val impactText = match.groupValues[2].trim().uppercase(Locale.ROOT)
            val reasoning = match.groupValues[3].trim().replace(Regex("\\s+"), " ")
            val impact = runCatching { SuggestionImpact.valueOf(impactText) }.getOrNull() ?: SuggestionImpact.MEDIUM
            if (title.isNotBlank() && reasoning.isNotBlank()) {
                suggestions.add(Suggestion(title = title, details = normalizeSuggestionText(reasoning), impact = impact))
            }
        }

        if (suggestions.isNotEmpty()) return suggestions

        // Last-resort salvage parser: convert response paragraphs into suggestions.
        val paragraphs =
            normalized
                .lineSequence()
                .map { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("• ") }
                .filter { it.isNotBlank() }
                .filterNot {
                    val lower = it.lowercase(Locale.ROOT)
                    lower.startsWith("title:") || lower.startsWith("impact:") || lower.startsWith("reasoning:")
                }
                .toList()
                .joinToString("\n")
                .split(Regex("\\n{2,}|(?<=\\.)\\s+(?=[A-Z])"))
                .map { it.trim() }
                .filter { it.length >= 30 }

        paragraphs.take(6).forEach { paragraph ->
            val impact =
                when {
                    Regex(
                        "\\bcritical|severe|block|blocking|high\\b",
                        RegexOption.IGNORE_CASE,
                    ).containsMatchIn(paragraph) -> SuggestionImpact.HIGH
                    Regex("\\bminor|optional|small|low\\b", RegexOption.IGNORE_CASE).containsMatchIn(paragraph) -> SuggestionImpact.LOW
                    else -> SuggestionImpact.MEDIUM
                }
            val title =
                paragraph
                    .substringBefore(".")
                    .substringBefore(":")
                    .trim()
                    .take(70)
                    .ifBlank { "Startup optimization suggestion" }
            suggestions.add(Suggestion(title = title, details = normalizeSuggestionText(paragraph), impact = impact))
        }

        return suggestions
    }

    private fun parseJsonLikeSuggestions(raw: String): List<Suggestion> {
        val suggestions = mutableListOf<Suggestion>()
        val payload = extractJsonPayload(raw)
        val objectRegex = Regex("(?s)\\{[^{}]*}")
        objectRegex.findAll(payload).forEach { match ->
            val block = match.value
            val title = extractJsonField(block, "title")
            val impactText = extractJsonField(block, "impact")?.uppercase(Locale.ROOT)
            val reasoning = extractJsonField(block, "reasoning")
            if (!title.isNullOrBlank() && !reasoning.isNullOrBlank()) {
                val impact = runCatching { SuggestionImpact.valueOf((impactText ?: "MEDIUM")) }.getOrNull() ?: SuggestionImpact.MEDIUM
                suggestions.add(Suggestion(title = title, details = normalizeSuggestionText(reasoning), impact = impact))
            }
        }
        return suggestions
    }

    private fun parseRiskAdjustmentPayload(
        response: String,
        baseRiskScore: Int,
    ): AiRiskResult? {
        val sanitized =
            response
                .replace(Regex("(?is)<think>.*?</think>"), "")
                .replace(Regex("(?is)<analysis>.*?</analysis>"), "")
                .replace(Regex("(?is)```(?:json)?"), "")
                .replace("```", "")
                .trim()
        val payload = extractOuterJsonObject(sanitized) ?: extractJsonPayload(sanitized)
        val explicitRiskScore =
            extractJsonNumberField(payload, "risk_score")
                ?.toDoubleOrNull()
                ?.roundToInt()
                ?.coerceIn(0, 100)
        val reason = extractJsonField(payload, "reason")?.trim().orEmpty()
        val overallConfidence =
            extractJsonNumberField(payload, "confidence")
                ?.toDoubleOrNull()
                ?.coerceIn(0.0, 1.0)

        val adjustmentsBlock = extractJsonArrayField(payload, "risk_adjustments")
        val adjustments = mutableListOf<AiRiskAdjustment>()
        if (!adjustmentsBlock.isNullOrBlank()) {
            Regex("(?s)\\{[^{}]*}").findAll(adjustmentsBlock).forEach { match ->
                val block = match.value
                val factor = extractJsonField(block, "factor")?.trim().orEmpty()
                val evidence = normalizeSuggestionText(extractJsonField(block, "evidence")?.trim().orEmpty())
                val delta = extractJsonNumberField(block, "delta")?.toDoubleOrNull()?.roundToInt()
                val confidence =
                    extractJsonNumberField(block, "confidence")
                        ?.toDoubleOrNull()
                        ?.coerceIn(0.0, 1.0)
                        ?: 0.0

                if (factor.isBlank() || evidence.isBlank() || delta == null) return@forEach
                val boundedDelta = delta.coerceIn(-10, 10)
                adjustments.add(
                    AiRiskAdjustment(
                        factor = factor,
                        delta = boundedDelta,
                        confidence = confidence,
                        evidence = evidence,
                    ),
                )
            }
        }

        val effectiveConfidence =
            overallConfidence
                ?: adjustments.takeIf { it.isNotEmpty() }?.map { it.confidence }?.average()?.coerceIn(0.0, 1.0)
                ?: 0.0
        val weightedDelta = adjustments.sumOf { adjustment -> adjustment.delta * adjustment.confidence }
        val derivedDelta = (weightedDelta * effectiveConfidence).roundToInt().coerceIn(-20, 20)
        val modelRiskScore = explicitRiskScore ?: (baseRiskScore + derivedDelta).coerceIn(0, 100)
        val appliedDelta = modelRiskScore - baseRiskScore
        if (explicitRiskScore == null && adjustments.isEmpty()) {
            val looseScore = extractLooseRiskScore(sanitized) ?: return null
            return AiRiskResult(
                enabled = true,
                used = true,
                baseRiskScore = baseRiskScore,
                modelRiskScore = looseScore,
                appliedDelta = looseScore - baseRiskScore,
                confidence = overallConfidence,
                parseStatus = AiRiskParseStatus.VALID,
                summary = reason.ifBlank { null },
                adjustments = emptyList(),
            )
        }
        return AiRiskResult(
            enabled = true,
            used = true,
            baseRiskScore = baseRiskScore,
            modelRiskScore = modelRiskScore,
            appliedDelta = appliedDelta,
            confidence = effectiveConfidence,
            parseStatus = AiRiskParseStatus.VALID,
            summary = reason.ifBlank { null },
            adjustments = adjustments,
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

    private fun normalizeSuggestionText(text: String): String {
        val flattenedCodeFences =
            text.replace(Regex("(?is)```(?:[a-zA-Z0-9_+\\-]+)?\\s*([\\s\\S]*?)```")) { match ->
                val inline = match.groupValues[1].trim().replace(Regex("\\s+"), " ")
                if (inline.isBlank()) "" else "`$inline`"
            }
        return flattenedCodeFences
            .replace(Regex("`{2,}"), "`")
            .trim()
    }

    private fun extractJsonPayload(text: String): String {
        val arrayStart = text.indexOf('[')
        val arrayEnd = text.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return text.substring(arrayStart, arrayEnd + 1)
        }
        val objectStart = text.indexOf('{')
        val objectEnd = text.lastIndexOf('}')
        if (objectStart >= 0 && objectEnd > objectStart) {
            return text.substring(objectStart, objectEnd + 1)
        }
        return text
    }

    private fun extractJsonArrayField(
        block: String,
        field: String,
    ): String? {
        val doubleQuoted =
            Regex("""(?is)"$field"\s*:\s*\[([\s\S]*?)]""")
                .find(block)
                ?.groupValues
                ?.get(1)
        if (!doubleQuoted.isNullOrBlank()) return doubleQuoted

        val singleQuoted =
            Regex("""(?is)'$field'\s*:\s*\[([\s\S]*?)]""")
                .find(block)
                ?.groupValues
                ?.get(1)
        return singleQuoted
    }

    private fun extractJsonField(
        block: String,
        field: String,
    ): String? {
        val doubleQuoted =
            Regex("""(?is)"$field"\s*:\s*"((?:\\.|[^"\\])*)"""")
                .find(block)
                ?.groupValues
                ?.get(1)
        if (!doubleQuoted.isNullOrBlank()) return jsonUnescape(doubleQuoted).trim()

        val singleQuoted =
            Regex("""(?is)'$field'\s*:\s*'((?:\\.|[^'\\])*)'""")
                .find(block)
                ?.groupValues
                ?.get(1)
        if (!singleQuoted.isNullOrBlank()) return singleQuoted.trim()

        val bare =
            Regex("""(?is)"$field"\s*:\s*([A-Za-z_]+)""")
                .find(block)
                ?.groupValues
                ?.get(1)
        return bare?.trim()
    }

    private fun extractJsonNumberField(
        block: String,
        field: String,
    ): String? {
        val number =
            Regex("""(?is)"$field"\s*:\s*(-?\d+(?:\.\d+)?)""")
                .find(block)
                ?.groupValues
                ?.get(1)
        if (!number.isNullOrBlank()) return number

        val quotedNumber =
            Regex("""(?is)"$field"\s*:\s*"(-?\d+(?:\.\d+)?)"""")
                .find(block)
                ?.groupValues
                ?.get(1)
        return quotedNumber
    }

    private fun extractLooseRiskScore(text: String): Int? {
        val matches =
            listOf(
                Regex("""(?is)\brisk[_\s-]*score\b\s*[:=]\s*(\d{1,3})"""),
                Regex("""(?is)\bscore\b\s*[:=]\s*(\d{1,3})\s*/\s*100"""),
            )
        for (regex in matches) {
            val raw = regex.find(text)?.groupValues?.getOrNull(1) ?: continue
            val value = raw.toIntOrNull() ?: continue
            return value.coerceIn(0, 100)
        }
        return null
    }

    private fun summarizeRiskSignals(findings: List<Finding>): RiskSignalSummary {
        val joinedText = findings.joinToString(" ") { "${it.title} ${it.description}" }.lowercase(Locale.ROOT)
        return RiskSignalSummary(
            infoCount = findings.count { it.severity.name == "INFO" },
            warnCount = findings.count { it.severity.name == "WARN" },
            criticalCount = findings.count { it.severity.name == "CRITICAL" },
            blockingCount = Regex("\\bblocking|sleep|runblocking\\b").findAll(joinedText).count(),
            networkCount = Regex("\\bnetwork|http|retrofit|okhttp|urlsession\\b").findAll(joinedText).count(),
            syncIoCount = Regex("\\bfile\\s*i/o|database|sharedpreferences|sqlite|room\\b").findAll(joinedText).count(),
            heavyInitCount = Regex("\\bheavy initialization|loops|instantiations\\b").findAll(joinedText).count(),
            duplicateCallCount = Regex("\\bduplicate|repeated|multiple times|called .* times\\b").findAll(joinedText).count(),
        )
    }

    private fun jsonUnescape(value: String): String {
        return value
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun fallbackSuggestions(findings: List<Finding>): List<Suggestion> {
        if (findings.isEmpty()) {
            return listOf(
                Suggestion(
                    title = "Startup path looks clean",
                    details = "No high-signal startup risks were detected. Re-run analysis after major startup code changes.",
                    impact = SuggestionImpact.LOW,
                ),
            )
        }

        val hasBlocking = findings.any { it.severity.name == "CRITICAL" }
        val hasIoOrNetwork =
            findings.any { finding ->
                val text = "${finding.title} ${finding.description}".lowercase(Locale.ROOT)
                "network" in text || "database" in text || "file" in text || "sharedpreferences" in text
            }

        val base = mutableListOf<Suggestion>()
        if (hasBlocking) {
            base.add(
                Suggestion(
                    title = "Remove blocking operations from startup",
                    details =
                        "Move blocking calls (for example sleep/runBlocking) " +
                            "off the critical startup path to reduce time-to-interactive.",
                    impact = SuggestionImpact.HIGH,
                ),
            )
        }
        if (hasIoOrNetwork) {
            base.add(
                Suggestion(
                    title = "Defer I/O and network initialization",
                    details =
                        "Delay non-essential database, " +
                            "file, and network work until after first screen render.",
                    impact = SuggestionImpact.HIGH,
                ),
            )
        }
        base.add(
            Suggestion(
                title = "Split startup into critical vs deferred tasks",
                details =
                    "Keep only rendering-critical setup in main/onCreate " +
                        "and schedule optional initialization asynchronously.",
                impact = SuggestionImpact.MEDIUM,
            ),
        )

        return base.take(6)
    }

    companion object {
        fun getInstance(project: Project): AiSuggestionService = project.getService(AiSuggestionService::class.java)
    }

    private fun logInfo(message: String) {
        logger.info(message)
        println("[OpenReviewer][AiSuggestionService][INFO] $message")
    }

    private fun logWarn(message: String) {
        logger.warn(message)
        System.err.println("[OpenReviewer][AiSuggestionService][WARN] $message")
    }

    private fun logWarn(
        message: String,
        error: Throwable,
    ) {
        logger.warn(message, error)
        System.err.println("[OpenReviewer][AiSuggestionService][WARN] $message")
        error.printStackTrace(System.err)
    }
}

/**
 * Small, safe context for suggestion generation. The prompt intentionally excludes full files/codebase.
 */
data class AiSuggestionContext(
    val platform: String,
    val entryPointSummary: String,
    val findings: List<Finding>,
    val prompt: String,
)

private data class RiskSignalSummary(
    val infoCount: Int,
    val warnCount: Int,
    val criticalCount: Int,
    val blockingCount: Int,
    val networkCount: Int,
    val syncIoCount: Int,
    val heavyInitCount: Int,
    val duplicateCallCount: Int,
)
