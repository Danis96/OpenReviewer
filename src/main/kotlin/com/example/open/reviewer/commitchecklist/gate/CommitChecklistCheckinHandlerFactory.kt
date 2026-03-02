package com.example.open.reviewer.commitchecklist.gate

import com.example.open.reviewer.commitchecklist.spec.CommitChecklistSpecParser
import com.example.open.reviewer.commitchecklist.spec.CommitChecklistSpecService
import com.example.open.reviewer.commitchecklist.ui.CommitChecklistDialog
import com.example.open.reviewer.commitchecklist.validation.CommitChecklistValidationConfig
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import java.nio.file.Files

class CommitChecklistCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(
        panel: CheckinProjectPanel,
        commitContext: CommitContext,
    ): CheckinHandler {
        return object : CheckinHandler() {
            override fun beforeCheckin(): ReturnResult {
                val project = panel.project
                val specService = CommitChecklistSpecService.getInstance(project)
                val specPath = specService.refreshSpecPath() ?: return ReturnResult.COMMIT

                val settings = CommitChecklistGateSettingsService.getInstance(project)
                val mode = settings.enforcementMode()
                val requireDescription = settings.requireDescription()

                val commitMessage = panel.commitMessage
                val alreadyComplete =
                    CommitChecklistCommitMessageParser.hasCompleteChecklistMetadata(
                        commitMessage = commitMessage,
                        requireDescription = requireDescription,
                    )
                if (alreadyComplete) {
                    return ReturnResult.COMMIT
                }

                val spec =
                    runCatching {
                        CommitChecklistSpecParser().parse(Files.readString(specPath), specPath)
                    }.getOrElse {
                        return ReturnResult.COMMIT
                    }

                val validationConfig =
                    CommitChecklistValidationConfig(
                        requireTypeOfChange = true,
                        requiredChecklistItemIndices = spec.checklistItems.indices.toSet(),
                        requireDescription = requireDescription,
                    )
                val dialog =
                    CommitChecklistDialog(
                        project = project,
                        spec = spec,
                        validationConfig = validationConfig,
                    )
                val result = dialog.showAndGetResult()

                return when {
                    result != null -> ReturnResult.COMMIT
                    mode == CommitChecklistEnforcementMode.BLOCK -> ReturnResult.CANCEL
                    else -> {
                        val proceed =
                            Messages.showYesNoDialog(
                                project,
                                "Checklist was not completed. Continue commit anyway?",
                                "Commit Checklist",
                                "Commit Anyway",
                                "Back",
                                Messages.getWarningIcon(),
                            )
                        if (proceed == Messages.YES) ReturnResult.COMMIT else ReturnResult.CANCEL
                    }
                }
            }
        }
    }
}
