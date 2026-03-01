package com.example.open.reviewer.commitchecklist.spec

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class CommitChecklistSpecStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        CommitChecklistSpecService.getInstance(project).scanOnProjectOpen()
    }
}

