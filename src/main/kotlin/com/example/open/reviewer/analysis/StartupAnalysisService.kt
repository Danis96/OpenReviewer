package com.example.open.reviewer.analysis

import com.example.open.reviewer.ai.AiSuggestionService
import com.example.open.reviewer.analysis.analyzer.HeuristicStartupAnalyzer
import com.example.open.reviewer.analysis.analyzer.StartupAnalyzer
import com.example.open.reviewer.analysis.scanner.StartupEntryPointDetector
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class StartupAnalysisService(private val project: Project) {
    private val analyzers: List<StartupAnalyzer> =
        listOf(
            HeuristicStartupAnalyzer(),
        )

    private val scoringEngine = RiskScoringEngine()
    private val aiSuggestionService = AiSuggestionService.getInstance(project)
    private val entryPointDetector = StartupEntryPointDetector()

    fun analyzeStartup(indicator: ProgressIndicator): StartupAnalysisResult {
        indicator.text = "Analyzing startup"
        indicator.isIndeterminate = false

        val analyzedEntryPoints =
            entryPointDetector.detect(project, indicator)
                .map { it.file.name }
                .distinct()

        val findings = mutableListOf<Finding>()
        val total = analyzers.size.coerceAtLeast(1)

        analyzers.forEachIndexed { index, analyzer ->
            indicator.checkCanceled()
            indicator.text2 = "Running analyzer ${index + 1} of $total"
            indicator.fraction = index.toDouble() / total
            findings += analyzer.analyze(project, indicator)
        }

        val deterministicScore = scoringEngine.score(findings)
        val sortedFindings = findings.sortedByDescending { scoringEngine.severityRank(it.severity) }

        indicator.text2 = "Generating AI suggestions"
        val suggestions = anchorSuggestions(aiSuggestionService.generateSuggestions(sortedFindings, indicator), sortedFindings)
        indicator.text2 = "Calibrating AI risk adjustment"
        val aiRisk =
            aiSuggestionService.generateRiskAdjustment(
                findings = sortedFindings,
                baseRiskScore = deterministicScore.riskScore,
                indicator = indicator,
            )
        val finalScoreValue = aiRisk.modelRiskScore ?: deterministicScore.riskScore
        val mergedScore = scoringEngine.scoreFromValue(finalScoreValue)

        indicator.fraction = 1.0
        return StartupAnalysisResult(
            riskScore = mergedScore.riskScore,
            riskLevel = mergedScore.riskLevel,
            findings = sortedFindings,
            suggestions = suggestions,
            analyzedEntryPoints = analyzedEntryPoints,
            aiRisk = aiRisk,
        )
    }

    companion object {
        fun getInstance(project: Project): StartupAnalysisService = project.getService(StartupAnalysisService::class.java)
    }

    private fun anchorSuggestions(
        suggestions: List<Suggestion>,
        findings: List<Finding>,
    ): List<Suggestion> {
        if (suggestions.isEmpty()) return suggestions
        if (findings.isEmpty()) return suggestions
        return suggestions.map { suggestion ->
            if (!suggestion.filePath.isNullOrBlank() && (suggestion.line ?: 0) > 0) {
                suggestion
            } else {
                val best = pickBestFindingAnchor(suggestion, findings) ?: return@map suggestion
                suggestion.copy(
                    filePath = best.filePath,
                    line = best.line,
                    codeSnippet = suggestion.codeSnippet ?: best.codeSnippet,
                )
            }
        }
    }

    private fun pickBestFindingAnchor(
        suggestion: Suggestion,
        findings: List<Finding>,
    ): Finding? {
        val candidates = findings.filter { !it.filePath.isNullOrBlank() && (it.line ?: 0) > 0 }
        if (candidates.isEmpty()) return null

        val suggestionTokens = tokenize("${suggestion.title} ${suggestion.details}")
        val scored =
            candidates.map { finding ->
                val findingTokens = tokenize("${finding.title} ${finding.description} ${finding.codeSnippet.orEmpty()}")
                val overlap = suggestionTokens.intersect(findingTokens).size
                val severityBoost =
                    when (finding.severity) {
                        FindingSeverity.CRITICAL -> 3
                        FindingSeverity.WARN -> 2
                        FindingSeverity.INFO -> 1
                    }
                finding to (overlap * 10 + severityBoost)
            }
        return scored.maxByOrNull { it.second }?.first
    }

    private fun tokenize(text: String): Set<String> {
        return Regex("[A-Za-z_][A-Za-z0-9_]{2,}")
            .findAll(text.lowercase())
            .map { it.value }
            .toSet()
    }
}
