package com.example.open.reviewer.commitchecklist.spec

import java.nio.file.Path

class CommitChecklistSpecParser {
    fun parse(
        markdown: String,
        sourcePath: Path? = null,
    ): ChecklistSpec {
        return runCatching {
            val sections = splitBySections(markdown)
            ChecklistSpec(
                descriptionTemplate = parseTextBlock(sections[SpecSection.DESCRIPTION]),
                typeOptions = parseTypeOptions(sections[SpecSection.TYPE_OF_CHANGE]),
                checklistItems = parseChecklistItems(sections[SpecSection.CHECKLIST]),
                reviewersGuidance = parseTextBlock(sections[SpecSection.REVIEWERS_GUIDANCE]),
                version = parseVersion(markdown),
                sourcePath = sourcePath,
            )
        }.getOrElse {
            ChecklistSpec(
                descriptionTemplate = "",
                typeOptions = emptyList(),
                checklistItems = emptyList(),
                reviewersGuidance = "",
                version = DEFAULT_VERSION,
                sourcePath = sourcePath,
            )
        }
    }

    private fun splitBySections(markdown: String): Map<SpecSection, List<String>> {
        val sections = linkedMapOf<SpecSection, MutableList<String>>()
        var activeSection: SpecSection? = null

        markdown.lineSequence().forEach { rawLine ->
            val heading = parseHeading(rawLine)
            if (heading != null) {
                activeSection = resolveSection(heading)
                if (activeSection != null) {
                    sections.putIfAbsent(activeSection, mutableListOf())
                }
            } else if (activeSection != null) {
                sections.getValue(activeSection).add(rawLine)
            }
        }

        return sections
    }

    private fun parseHeading(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("#")) return null
        return trimmed.trimStart('#').trim().ifBlank { null }
    }

    private fun resolveSection(heading: String): SpecSection? {
        val normalized =
            heading
                .lowercase()
                .replace('’', '\'')
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        return when (normalized) {
            "description" -> SpecSection.DESCRIPTION
            "type of change" -> SpecSection.TYPE_OF_CHANGE
            "checklist" -> SpecSection.CHECKLIST
            "reviewer s guidance", "reviewers guidance", "reviewer guidance" -> SpecSection.REVIEWERS_GUIDANCE
            else -> null
        }
    }

    private fun parseTextBlock(lines: List<String>?): String {
        if (lines.isNullOrEmpty()) return ""
        return lines
            .dropWhile { it.trim().isEmpty() }
            .dropLastWhile { it.trim().isEmpty() }
            .joinToString("\n")
            .trim()
    }

    private fun parseTypeOptions(lines: List<String>?): List<String> {
        if (lines.isNullOrEmpty()) return emptyList()
        return lines
            .mapNotNull { extractListText(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun parseChecklistItems(lines: List<String>?): List<ChecklistItem> {
        if (lines.isNullOrEmpty()) return emptyList()

        val checkboxItems =
            lines.mapNotNull { line ->
                val match = CHECKBOX_ITEM_PATTERN.matchEntire(line.trim()) ?: return@mapNotNull null
                val checkedToken = match.groupValues[1]
                val text = match.groupValues[2].trim()
                if (text.isBlank()) return@mapNotNull null
                ChecklistItem(
                    text = text,
                    checked = checkedToken.equals("x", ignoreCase = true),
                )
            }
        if (checkboxItems.isNotEmpty()) return checkboxItems

        return lines
            .mapNotNull { extractListText(it) }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("[") }
            .map { ChecklistItem(text = it, checked = false) }
    }

    private fun extractListText(line: String): String? {
        val match = BULLET_ITEM_PATTERN.matchEntire(line.trim()) ?: return null
        val text = match.groupValues[1].trim()
        return text.ifBlank { null }
    }

    private fun parseVersion(markdown: String): String {
        val match = VERSION_PATTERN.find(markdown) ?: return DEFAULT_VERSION
        return match.groupValues[1].trim().ifBlank { DEFAULT_VERSION }
    }

    private enum class SpecSection {
        DESCRIPTION,
        TYPE_OF_CHANGE,
        CHECKLIST,
        REVIEWERS_GUIDANCE,
    }

    companion object {
        private const val DEFAULT_VERSION = "1"
        private val VERSION_PATTERN = Regex("""openreviewer\s*:\s*version\s*=\s*([A-Za-z0-9._-]+)""", setOf(RegexOption.IGNORE_CASE))
        private val BULLET_ITEM_PATTERN = Regex("""^(?:[-*+]|\d+\.)\s+(.+)$""")
        private val CHECKBOX_ITEM_PATTERN = Regex("""^(?:[-*+]|\d+\.)\s*\[([xX ])]\s*(.+)$""")
    }
}
