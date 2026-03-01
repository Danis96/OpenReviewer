package com.example.open.reviewer.commitchecklist.setup

import com.example.open.reviewer.commitchecklist.spec.ChecklistItem
import com.example.open.reviewer.commitchecklist.spec.ChecklistSpec
import java.nio.file.Path

enum class CommitChecklistTemplatePreset(
    val label: String,
) {
    BASIC("Basic"),
    STRICT("Strict"),
    LIGHTWEIGHT("Lightweight"),
}

data class CommitChecklistInstallRequest(
    val targetPath: Path,
    val preset: CommitChecklistTemplatePreset,
)

internal object CommitChecklistTemplateFactory {
    fun createSpec(
        preset: CommitChecklistTemplatePreset,
        targetPath: Path,
    ): ChecklistSpec {
        return when (preset) {
            CommitChecklistTemplatePreset.BASIC ->
                ChecklistSpec(
                    descriptionTemplate = "Describe what changed and why.",
                    typeOptions = listOf("Feature", "Bug fix", "Refactor", "Chore"),
                    checklistItems =
                        listOf(
                            ChecklistItem("Added or updated tests where needed", checked = false),
                            ChecklistItem("Self-review completed", checked = false),
                            ChecklistItem("Documentation updated if applicable", checked = false),
                        ),
                    reviewersGuidance = "Focus on correctness, regressions, and migration risk.",
                    version = "1",
                    sourcePath = targetPath,
                )

            CommitChecklistTemplatePreset.STRICT ->
                ChecklistSpec(
                    descriptionTemplate = "Describe change scope, motivation, and rollout plan.",
                    typeOptions = listOf("Feature", "Bug fix", "Refactor", "Performance", "Security", "Chore"),
                    checklistItems =
                        listOf(
                            ChecklistItem("Tests added/updated and passing", checked = false),
                            ChecklistItem("Backward compatibility reviewed", checked = false),
                            ChecklistItem("Observability/logging updated if needed", checked = false),
                            ChecklistItem("Migration notes included (if required)", checked = false),
                            ChecklistItem("Self-review completed", checked = false),
                        ),
                    reviewersGuidance = "Prioritize regression risk, edge cases, and deployment impact.",
                    version = "1",
                    sourcePath = targetPath,
                )

            CommitChecklistTemplatePreset.LIGHTWEIGHT ->
                ChecklistSpec(
                    descriptionTemplate = "Summarize the change in 1-3 sentences.",
                    typeOptions = listOf("Feature", "Bug fix", "Chore"),
                    checklistItems =
                        listOf(
                            ChecklistItem("Self-review completed", checked = false),
                            ChecklistItem("Tests/docs updated if needed", checked = false),
                        ),
                    reviewersGuidance = "Focus on functional correctness and obvious regressions.",
                    version = "1",
                    sourcePath = targetPath,
                )
        }
    }
}

