package com.example.open.reviewer.commitchecklist.setup

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal object CommitChecklistGitSetupWorkflow {
    private const val COMMIT_MESSAGE = "chore(openreviewer): add commit checklist spec"
    private const val GIT_TIMEOUT_SECONDS = 20L

    fun promptAndRun(
        project: Project,
        gitRoot: Path,
        createdFiles: List<Path>,
    ) {
        if (createdFiles.isEmpty()) return

        val choice =
            Messages.showDialog(
                project,
                "Setup file is created. Do you want to stage, commit, and optionally push it now?",
                "Optional: Commit & Push Setup",
                arrayOf("Skip", "Stage + Commit", "Stage + Commit + Push"),
                0,
                Messages.getQuestionIcon(),
            )
        when (choice) {
            1 -> stageAndCommit(project, gitRoot, createdFiles)
            2 -> stageCommitAndPush(project, gitRoot, createdFiles)
            else -> return
        }
    }

    private fun stageCommitAndPush(
        project: Project,
        gitRoot: Path,
        createdFiles: List<Path>,
    ) {
        if (!stageAndCommit(project, gitRoot, createdFiles)) return
        val pushResult = runGit(gitRoot, listOf("push"))
        if (!pushResult.success) {
            notify(project, NotificationType.ERROR, "Push failed: ${pushResult.output.ifBlank { "unknown error" }}")
            return
        }
        notify(project, NotificationType.INFORMATION, "OpenReviewer setup committed and pushed to current branch.")
    }

    private fun stageAndCommit(
        project: Project,
        gitRoot: Path,
        createdFiles: List<Path>,
    ): Boolean {
        val stageArgs = buildStageArgs(gitRoot, createdFiles)
        val stageResult = runGit(gitRoot, stageArgs)
        if (!stageResult.success) {
            notify(project, NotificationType.ERROR, "Staging failed: ${stageResult.output.ifBlank { "unknown error" }}")
            return false
        }

        val commitResult = runGit(gitRoot, listOf("commit", "-m", COMMIT_MESSAGE))
        if (!commitResult.success) {
            notify(project, NotificationType.ERROR, "Commit failed: ${commitResult.output.ifBlank { "unknown error" }}")
            return false
        }

        notify(project, NotificationType.INFORMATION, "OpenReviewer setup committed on current branch.")
        return true
    }

    internal fun buildStageArgs(
        gitRoot: Path,
        createdFiles: List<Path>,
    ): List<String> {
        return buildList {
            add("add")
            createdFiles.forEach { path ->
                add(gitRoot.relativize(path).toString().replace('\\', '/'))
            }
        }
    }

    private fun runGit(
        gitRoot: Path,
        args: List<String>,
    ): GitCommandResult {
        return runCatching {
            val process =
                ProcessBuilder(buildList {
                    add("git")
                    add("-C")
                    add(gitRoot.toString())
                    addAll(args)
                })
                    .redirectErrorStream(true)
                    .start()

            val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return GitCommandResult(success = false, output = "git command timed out")
            }

            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            GitCommandResult(success = process.exitValue() == 0, output = output)
        }.getOrElse {
            GitCommandResult(success = false, output = it.message.orEmpty())
        }
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

    internal data class GitCommandResult(
        val success: Boolean,
        val output: String,
    )
}
