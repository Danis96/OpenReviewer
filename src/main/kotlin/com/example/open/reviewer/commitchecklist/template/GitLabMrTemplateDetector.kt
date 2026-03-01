package com.example.open.reviewer.commitchecklist.template

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList

data class GitLabMrTemplateDetectionResult(
    val templatesDir: Path,
    val foundTemplates: List<Path>,
) {
    val isMissing: Boolean get() = foundTemplates.isEmpty()
}

object GitLabMrTemplateDetector {
    fun detect(gitRoot: Path): GitLabMrTemplateDetectionResult {
        val templatesDir = gitRoot.resolve(".gitlab").resolve("merge_request_templates")
        if (!Files.isDirectory(templatesDir)) {
            return GitLabMrTemplateDetectionResult(
                templatesDir = templatesDir,
                foundTemplates = emptyList(),
            )
        }

        val found =
            Files.list(templatesDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".md") }
                    .sorted()
                    .toList()
            }

        return GitLabMrTemplateDetectionResult(
            templatesDir = templatesDir,
            foundTemplates = found,
        )
    }
}

