package com.example.open.reviewer.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class RiskScoringEngineTest {
    private val engine = RiskScoringEngine()

    @Test
    fun `returns low when no findings`() {
        val result = engine.score(emptyList())

        assertEquals(0, result.riskScore)
        assertEquals(RiskLevel.LOW, result.riskLevel)
    }

    @Test
    fun `computes deterministic score and medium level`() {
        val findings =
            listOf(
                finding(FindingSeverity.INFO),
                finding(FindingSeverity.WARN),
            )

        val result = engine.score(findings)

        assertEquals(35, result.riskScore)
        assertEquals(RiskLevel.MEDIUM, result.riskLevel)
    }

    @Test
    fun `caps score at 100 and marks high`() {
        val findings = List(4) { finding(FindingSeverity.CRITICAL) }

        val result = engine.score(findings)

        assertEquals(100, result.riskScore)
        assertEquals(RiskLevel.HIGH, result.riskLevel)
    }

    @Test
    fun `uses configured thresholds`() {
        val customEngine =
            RiskScoringEngine(
                RiskScoringConfig(
                    weights = RiskScoringConfig.SeverityWeights(info = 10, warn = 20, critical = 30),
                    thresholds = RiskScoringConfig.LevelThresholds(mediumMin = 20, highMin = 40),
                ),
            )

        val result = customEngine.score(listOf(finding(FindingSeverity.WARN), finding(FindingSeverity.WARN)))

        assertEquals(40, result.riskScore)
        assertEquals(RiskLevel.HIGH, result.riskLevel)
    }

    private fun finding(severity: FindingSeverity): Finding {
        return Finding(
            title = "t",
            description = "d",
            severity = severity,
        )
    }
}
