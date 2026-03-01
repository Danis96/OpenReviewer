package com.example.open.reviewer.commitchecklist.setup

import com.example.open.reviewer.commitchecklist.vcs.CommitChecklistVcsService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class InstallCommitChecklistSpecAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        CommitChecklistSpecInstallWorkflow.run(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val enabled = project != null && CommitChecklistVcsService.getInstance(project).resolveGitRepositoryRoot() != null
        e.presentation.isEnabled = enabled
    }
}

