package com.example.open.reviewer.commitchecklist.template

import java.nio.file.Files
import java.nio.file.Path

data class GitHubPrTemplateDetectionResult(
    val expectedPath: Path,
    val foundPath: Path?,
) {
    val isPresent: Boolean get() = foundPath != null
}

object GitHubPrTemplateDetector {
    fun detect(gitRoot: Path): GitHubPrTemplateDetectionResult {
        val expected = gitRoot.resolve(".github").resolve("pull_request_template.md")
        val found = if (Files.isRegularFile(expected)) expected.normalize() else null
        return GitHubPrTemplateDetectionResult(
            expectedPath = expected,
            foundPath = found,
        )
    }
}

