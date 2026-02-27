package com.example.open.reviewer.tour.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class OpenReviewrTourStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        OpenReviewrTourIndexService.getInstance(project).scheduleRebuild(immediate = true)
    }
}
