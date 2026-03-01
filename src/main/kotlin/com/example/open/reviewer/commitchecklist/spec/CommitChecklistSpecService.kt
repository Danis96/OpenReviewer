package com.example.open.reviewer.commitchecklist.spec

import com.example.open.reviewer.commitchecklist.vcs.CommitChecklistVcsService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
@State(name = "OpenReviewerCommitChecklistSpec", storages = [Storage("open-reviewer-commit-checklist.xml")])
class CommitChecklistSpecService(
    private val project: Project,
) : PersistentStateComponent<CommitChecklistSpecState> {
    private var state = CommitChecklistSpecState()

    fun refreshSpecPath(): Path? {
        val gitRoot = CommitChecklistVcsService.getInstance(project).resolveGitRepositoryRoot()
        val resolvedPath =
            gitRoot?.let {
                CommitChecklistSpecSelector.selectExistingSpecPath(it)
            }
        state.specPath = resolvedPath?.toString()
        return resolvedPath
    }

    fun getSpecPath(): Path? = state.specPath?.let { Paths.get(it) }

    fun isSpecConfigured(): Boolean = state.specPath != null

    fun canonicalSpecPaths(gitRoot: Path): List<Path> = CommitChecklistSpecSelector.canonicalSpecPaths(gitRoot)

    fun scanOnProjectOpen() {
        if (CommitChecklistVcsService.getInstance(project).resolveGitRepositoryRoot() == null) return
        val resolved = refreshSpecPath()
        val shouldShowHint =
            MissingSpecHintPolicy.shouldShowHint(
                hasGitRoot = true,
                hasSpec = resolved != null,
                hintAlreadyShown = state.missingSpecHintShown,
            )
        if (!shouldShowHint) return
        maybeShowMissingSpecHint()
    }

    private fun maybeShowMissingSpecHint() {
        if (state.missingSpecHintShown) return

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Open Reviewer Notifications")
            .createNotification(
                "Commit checklist is not configured for this repository.",
                "Add `.openreviewer/COMMIT_CHECKLIST.md` to enable checklist enforcement.",
                NotificationType.INFORMATION,
            )
            .notify(project)
        state.missingSpecHintShown = true
    }

    override fun getState(): CommitChecklistSpecState = state

    override fun loadState(state: CommitChecklistSpecState) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): CommitChecklistSpecService = project.getService(CommitChecklistSpecService::class.java)
    }
}

data class CommitChecklistSpecState(
    var specPath: String? = null,
    var missingSpecHintShown: Boolean = false,
)
