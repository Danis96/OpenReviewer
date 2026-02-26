package com.example.open.reviewer.analysis

/**
 * Deterministic risk scoring for startup findings.
 *
 * Score formula:
 * - INFO findings add [weights.info]
 * - WARN findings add [weights.warn]
 * - CRITICAL findings add [weights.critical]
 *
 * The total is capped to 100.
 * Risk level thresholds are centralized in [RiskScoringConfig].
 */
class RiskScoringEngine(
    private val config: RiskScoringConfig = RiskScoringConfig.DEFAULT,
) {
    fun score(findings: List<Finding>): RiskScoreResult {
        val rawScore =
            findings.sumOf { finding ->
                when (finding.severity) {
                    FindingSeverity.INFO -> config.weights.info
                    FindingSeverity.WARN -> config.weights.warn
                    FindingSeverity.CRITICAL -> config.weights.critical
                }
            }

        return scoreFromValue(rawScore)
    }

    fun scoreFromValue(score: Int): RiskScoreResult {
        val riskScore = score.coerceIn(0, MAX_SCORE)
        return RiskScoreResult(riskScore = riskScore, riskLevel = riskLevelFor(riskScore))
    }

    fun riskLevelFor(score: Int): RiskLevel {
        return when {
            score >= config.thresholds.highMin -> RiskLevel.HIGH
            score >= config.thresholds.mediumMin -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }

    fun severityRank(severity: FindingSeverity): Int =
        when (severity) {
            FindingSeverity.CRITICAL -> 3
            FindingSeverity.WARN -> 2
            FindingSeverity.INFO -> 1
        }

    companion object {
        private const val MAX_SCORE = 100
    }
}

data class RiskScoreResult(
    val riskScore: Int,
    val riskLevel: RiskLevel,
)

data class RiskScoringConfig(
    val weights: SeverityWeights,
    val thresholds: LevelThresholds,
) {
    data class SeverityWeights(
        val info: Int,
        val warn: Int,
        val critical: Int,
    )

    data class LevelThresholds(
        val mediumMin: Int,
        val highMin: Int,
    )

    companion object {
        /**
         * Configure thresholds and weights in one place.
         */
        val DEFAULT =
            RiskScoringConfig(
                weights =
                    SeverityWeights(
                        info = 10,
                        warn = 25,
                        critical = 45,
                    ),
                thresholds =
                    LevelThresholds(
                        mediumMin = 35,
                        highMin = 70,
                    ),
            )
    }
}
