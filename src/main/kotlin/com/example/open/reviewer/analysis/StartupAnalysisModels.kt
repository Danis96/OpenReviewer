package com.example.open.reviewer.analysis

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

enum class FindingSeverity {
    INFO,
    WARN,
    CRITICAL,
}

enum class SuggestionImpact {
    LOW,
    MEDIUM,
    HIGH,
}

data class Finding(
    val title: String,
    val description: String,
    val severity: FindingSeverity,
    val filePath: String? = null,
    val line: Int? = null,
    val codeSnippet: String? = null,
)

data class StartupAnalysisResult(
    val riskScore: Int,
    val riskLevel: RiskLevel,
    val findings: List<Finding>,
    val suggestions: List<Suggestion>,
    val analyzedEntryPoints: List<String> = emptyList(),
    val aiRisk: AiRiskResult = AiRiskResult.disabled(baseRiskScore = riskScore),
)

data class Suggestion(
    val title: String,
    val details: String,
    val impact: SuggestionImpact,
)

enum class AiRiskParseStatus {
    DISABLED,
    CONFIG_FALLBACK,
    CLIENT_ERROR,
    PARSE_ERROR,
    VALID,
}

data class AiRiskAdjustment(
    val factor: String,
    val delta: Int,
    val confidence: Double,
    val evidence: String,
)

data class AiRiskResult(
    val enabled: Boolean,
    val used: Boolean,
    val baseRiskScore: Int,
    val modelRiskScore: Int?,
    val appliedDelta: Int,
    val confidence: Double?,
    val parseStatus: AiRiskParseStatus,
    val summary: String?,
    val adjustments: List<AiRiskAdjustment>,
) {
    companion object {
        fun disabled(baseRiskScore: Int): AiRiskResult {
            return AiRiskResult(
                enabled = false,
                used = false,
                baseRiskScore = baseRiskScore,
                modelRiskScore = null,
                appliedDelta = 0,
                confidence = null,
                parseStatus = AiRiskParseStatus.DISABLED,
                summary = null,
                adjustments = emptyList(),
            )
        }

        fun fallback(
            baseRiskScore: Int,
            parseStatus: AiRiskParseStatus,
        ): AiRiskResult {
            return AiRiskResult(
                enabled = true,
                used = false,
                baseRiskScore = baseRiskScore,
                modelRiskScore = null,
                appliedDelta = 0,
                confidence = null,
                parseStatus = parseStatus,
                summary = null,
                adjustments = emptyList(),
            )
        }
    }
}
