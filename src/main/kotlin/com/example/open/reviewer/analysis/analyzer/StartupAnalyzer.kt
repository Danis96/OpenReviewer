package com.example.open.reviewer.analysis.analyzer

import com.example.open.reviewer.analysis.Finding
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project

interface StartupAnalyzer {
    fun analyze(
        project: Project,
        indicator: ProgressIndicator,
    ): List<Finding>
}
