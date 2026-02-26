package com.example.open.reviewer.analysis.analyzer

import com.example.open.reviewer.analysis.Finding
import com.example.open.reviewer.analysis.scanner.StartupEntryPointDetector
import com.example.open.reviewer.analysis.scanner.StartupRiskScanner
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project

class HeuristicStartupAnalyzer : StartupAnalyzer {
    private val entryPointDetector = StartupEntryPointDetector()
    private val riskScanner = StartupRiskScanner(maxDepth = 2)

    override fun analyze(
        project: Project,
        indicator: ProgressIndicator,
    ): List<Finding> {
        val entryPoints = entryPointDetector.detect(project, indicator)
        if (entryPoints.isEmpty()) {
            return emptyList()
        }

        indicator.text2 = "Scanning startup call tree"
        return riskScanner.scan(entryPoints, indicator)
    }
}
