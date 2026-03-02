package com.example.open.reviewer.commitchecklist.gate

data class PushChecklistInspectionResult(
    val missingCommitSummaries: List<String>,
) {
    val isComplete: Boolean get() = missingCommitSummaries.isEmpty()
}

internal object CommitChecklistPushMetadataInspector {
    fun inspect(
        commitMessages: List<String>,
        commitSummaries: List<String>,
        requireDescription: Boolean,
    ): PushChecklistInspectionResult {
        val missing =
            commitMessages.mapIndexedNotNull { index, message ->
                val hasMetadata =
                    CommitChecklistCommitMessageParser.hasCompleteChecklistMetadata(
                        commitMessage = message,
                        requireDescription = requireDescription,
                    )
                if (hasMetadata) {
                    null
                } else {
                    commitSummaries.getOrNull(index) ?: "Commit #${index + 1}"
                }
            }
        return PushChecklistInspectionResult(missingCommitSummaries = missing)
    }
}

