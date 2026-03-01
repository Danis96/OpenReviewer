package com.example.open.reviewer.commitchecklist.spec

import java.nio.file.Path

data class ChecklistSpec(
    val descriptionTemplate: String,
    val typeOptions: List<String>,
    val checklistItems: List<ChecklistItem>,
    val reviewersGuidance: String,
    val version: String,
    val sourcePath: Path?,
)

data class ChecklistItem(
    val text: String,
    val checked: Boolean,
)

