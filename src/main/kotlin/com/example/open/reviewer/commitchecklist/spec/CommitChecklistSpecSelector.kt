package com.example.open.reviewer.commitchecklist.spec

import java.nio.file.Files
import java.nio.file.Path

internal object CommitChecklistSpecSelector {
    private const val SPEC_FILE_NAME = "COMMIT_CHECKLIST.md"
    private const val PREFERRED_FOLDER = ".openreviewer"

    fun canonicalSpecPaths(gitRoot: Path): List<Path> {
        return listOf(
            gitRoot.resolve(PREFERRED_FOLDER).resolve(SPEC_FILE_NAME),
            gitRoot.resolve(SPEC_FILE_NAME),
        )
    }

    fun selectExistingSpecPath(gitRoot: Path): Path? {
        return canonicalSpecPaths(gitRoot)
            .firstOrNull { Files.isRegularFile(it) }
            ?.normalize()
    }
}

