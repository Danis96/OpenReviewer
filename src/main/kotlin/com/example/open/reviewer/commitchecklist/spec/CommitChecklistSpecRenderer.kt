package com.example.open.reviewer.commitchecklist.spec

class CommitChecklistSpecRenderer {
    fun render(spec: ChecklistSpec): String {
        val normalizedVersion = spec.version.ifBlank { DEFAULT_VERSION }

        val description = normalizeMultiline(spec.descriptionTemplate)
        val typeOptions = spec.typeOptions.map { it.trim() }.filter { it.isNotBlank() }
        val checklistItems = spec.checklistItems.mapNotNull { normalizeChecklistItem(it) }
        val reviewersGuidance = normalizeMultiline(spec.reviewersGuidance)

        return buildString {
            append("<!-- openreviewer:version=").append(normalizedVersion).append(" -->\n\n")

            append("## Description\n")
            if (description.isNotEmpty()) {
                append(description).append('\n')
            }
            append('\n')

            append("## Type of Change\n")
            if (typeOptions.isNotEmpty()) {
                typeOptions.forEach { option ->
                    append("- ").append(option).append('\n')
                }
            }
            append('\n')

            append("## Checklist\n")
            if (checklistItems.isNotEmpty()) {
                checklistItems.forEach { item ->
                    append("- [")
                        .append(if (item.checked) "x" else " ")
                        .append("] ")
                        .append(item.text)
                        .append('\n')
                }
            }
            append('\n')

            append("## Reviewer’s Guidance\n")
            if (reviewersGuidance.isNotEmpty()) {
                append(reviewersGuidance).append('\n')
            }
        }.trimEnd() + "\n"
    }

    private fun normalizeMultiline(value: String): String {
        return value
            .lineSequence()
            .map { it.trim() }
            .dropWhile { it.isBlank() }
            .toList()
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
    }

    private fun normalizeChecklistItem(item: ChecklistItem): ChecklistItem? {
        val text = item.text.trim()
        if (text.isBlank()) return null
        return ChecklistItem(text = text, checked = item.checked)
    }

    companion object {
        private const val DEFAULT_VERSION = "1"
    }
}
