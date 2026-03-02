package com.example.open.reviewer.commitchecklist.gate

internal object CommitChecklistCommitMessageParser {
    fun hasCompleteChecklistMetadata(
        commitMessage: String,
        requireDescription: Boolean,
    ): Boolean {
        val block = extractOpenReviewerBlock(commitMessage) ?: return false
        val fields = parseFields(block)

        val hasType = fields["type"]?.isNotBlank() == true
        val hasChecklist = fields["checklist"]?.isNotBlank() == true
        val hasDescription = fields["description"]?.isNotBlank() == true

        if (!hasType || !hasChecklist) return false
        if (requireDescription && !hasDescription) return false
        return true
    }

    private fun extractOpenReviewerBlock(commitMessage: String): String? {
        val start = commitMessage.indexOf(OPEN_TAG)
        if (start < 0) return null
        val end = commitMessage.indexOf(CLOSE_TAG, start + OPEN_TAG.length)
        if (end < 0) return null
        return commitMessage.substring(start + OPEN_TAG.length, end)
    }

    private fun parseFields(block: String): Map<String, String> {
        return block
            .lineSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .associate { line ->
                val index = line.indexOf('=')
                val key = line.substring(0, index).trim().lowercase()
                val value = line.substring(index + 1).trim()
                key to value
            }
    }

    private const val OPEN_TAG = "[openreviewer]"
    private const val CLOSE_TAG = "[/openreviewer]"
}

