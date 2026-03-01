package com.example.open.reviewer.commitchecklist.setup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class CommitChecklistTemplateFactoryTest {
    @Test
    fun `all presets generate non-empty spec`() {
        val path = Path.of("/repo/.openreviewer/COMMIT_CHECKLIST.md")

        CommitChecklistTemplatePreset.entries.forEach { preset ->
            val spec = CommitChecklistTemplateFactory.createSpec(preset, path)
            assertTrue(spec.descriptionTemplate.isNotBlank())
            assertTrue(spec.typeOptions.isNotEmpty())
            assertTrue(spec.checklistItems.isNotEmpty())
            assertTrue(spec.reviewersGuidance.isNotBlank())
            assertTrue(spec.sourcePath == path)
        }
    }
}
