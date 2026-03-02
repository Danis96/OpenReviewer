package com.example.open.reviewer.commitchecklist.gate

import com.example.open.reviewer.commitchecklist.spec.CommitChecklistSpecParser
import com.example.open.reviewer.commitchecklist.spec.CommitChecklistSpecService
import com.example.open.reviewer.commitchecklist.ui.CommitChecklistDialog
import com.example.open.reviewer.commitchecklist.validation.CommitChecklistValidationConfig
import com.intellij.dvcs.push.PrePushHandler
import com.intellij.dvcs.push.PushInfo
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.nio.file.Files

class CommitChecklistPrePushHandler : PrePushHandler {
    override fun getPresentableName(): String = "OpenReviewer Commit Checklist"

    override fun handle(
        project: Project,
        pushDetails: MutableList<PushInfo>,
        indicator: ProgressIndicator,
    ): PrePushHandler.Result {
        val specService = CommitChecklistSpecService.getInstance(project)
        val specPath = specService.refreshSpecPath() ?: return PrePushHandler.Result.OK

        val settings = CommitChecklistGateSettingsService.getInstance(project)
        val mode = settings.enforcementMode()
        val requireDescription = settings.requireDescription()

        val commits = pushDetails.flatMap { it.commits }
        if (commits.isEmpty()) return PrePushHandler.Result.OK

        val inspection =
            CommitChecklistPushMetadataInspector.inspect(
                commitMessages = commits.map { it.fullMessage },
                commitSummaries = commits.map { it.subject },
                requireDescription = requireDescription,
            )
        if (inspection.isComplete) {
            return PrePushHandler.Result.OK
        }

        val spec =
            runCatching {
                CommitChecklistSpecParser().parse(Files.readString(specPath), specPath)
            }.getOrElse {
                return PrePushHandler.Result.OK
            }

        val dialog =
            CommitChecklistDialog(
                project = project,
                spec = spec,
                validationConfig =
                    CommitChecklistValidationConfig(
                        requireTypeOfChange = true,
                        requiredChecklistItemIndices = spec.checklistItems.indices.toSet(),
                        requireDescription = requireDescription,
                    ),
            )
        val dialogResult = dialog.showAndGetResult()
        if (dialogResult != null) {
            return PrePushHandler.Result.OK
        }

        return when (mode) {
            CommitChecklistEnforcementMode.BLOCK -> PrePushHandler.Result.ABORT
            CommitChecklistEnforcementMode.WARN -> {
                val previewList = inspection.missingCommitSummaries.take(3).joinToString(separator = "\n") { "• $it" }
                val moreCount = (inspection.missingCommitSummaries.size - 3).coerceAtLeast(0)
                val suffix = if (moreCount > 0) "\n…and $moreCount more commit(s)." else ""
                val proceed =
                    Messages.showYesNoDialog(
                        project,
                        "Some outgoing commits are missing checklist metadata:\n$previewList$suffix\n\nPush anyway?",
                        "Commit Checklist Push Gate",
                        "Push Anyway",
                        "Back",
                        Messages.getWarningIcon(),
                    )
                if (proceed == Messages.YES) {
                    PrePushHandler.Result.OK
                } else {
                    PrePushHandler.Result.ABORT
                }
            }
        }
    }
}

