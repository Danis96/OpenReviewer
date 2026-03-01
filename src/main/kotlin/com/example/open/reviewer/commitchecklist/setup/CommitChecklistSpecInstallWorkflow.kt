package com.example.open.reviewer.commitchecklist.setup

import com.example.open.reviewer.commitchecklist.spec.CommitChecklistSpecRenderer
import com.example.open.reviewer.commitchecklist.spec.CommitChecklistSpecService
import com.example.open.reviewer.commitchecklist.vcs.CommitChecklistVcsService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

object CommitChecklistSpecInstallWorkflow {
    fun run(
        project: Project,
        onInstalled: (() -> Unit)? = null,
    ) {
        val vcsService = CommitChecklistVcsService.getInstance(project)
        val specService = CommitChecklistSpecService.getInstance(project)
        val gitRoot = vcsService.resolveGitRepositoryRoot()
        if (gitRoot == null) {
            notify(project, NotificationType.WARNING, "OpenReviewer checklist setup is available only in Git repositories.")
            return
        }

        val locationOptions = specService.canonicalSpecPaths(gitRoot)
        val dialog = InstallCommitChecklistSpecDialog(project, locationOptions)
        if (!dialog.showAndGet()) return
        val request = dialog.installRequest() ?: return

        if (!confirmOverwriteIfNeeded(project, request.targetPath)) return

        runCatching {
            Files.createDirectories(request.targetPath.parent)
            val spec = CommitChecklistTemplateFactory.createSpec(request.preset, request.targetPath)
            Files.writeString(request.targetPath, CommitChecklistSpecRenderer().render(spec))
            specService.refreshSpecPath()
            openPath(project, request.targetPath)
            notify(
                project,
                NotificationType.INFORMATION,
                "Commit checklist spec installed at ${request.targetPath.toAbsolutePath()}. Next step: generate templates in Repo Setup tab.",
            )
            CommitChecklistGitSetupWorkflow.promptAndRun(
                project = project,
                gitRoot = gitRoot,
                createdFiles = listOf(request.targetPath),
            )
            onInstalled?.invoke()
        }.onFailure {
            notify(project, NotificationType.ERROR, "Failed to install commit checklist spec: ${it.message}")
        }
    }

    private fun confirmOverwriteIfNeeded(
        project: Project,
        targetPath: Path,
    ): Boolean {
        if (!Files.exists(targetPath)) return true
        val answer =
            Messages.showYesNoDialog(
                project,
                "File already exists:\n${targetPath.toAbsolutePath()}\n\nOverwrite it?",
                "Install Commit Checklist Spec",
                "Overwrite",
                "Cancel",
                Messages.getQuestionIcon(),
            )
        return answer == Messages.YES
    }

    private fun openPath(
        project: Project,
        path: Path,
    ) {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toAbsolutePath().toString()) ?: return
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }

    private fun notify(
        project: Project,
        type: NotificationType,
        message: String,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Open Reviewer Notifications")
            .createNotification(message, type)
            .notify(project)
    }
}
