package com.example.open.reviewer.commitchecklist.spec

internal object MissingSpecHintPolicy {
    fun shouldShowHint(
        hasGitRoot: Boolean,
        hasSpec: Boolean,
        hintAlreadyShown: Boolean,
    ): Boolean {
        if (!hasGitRoot) return false
        if (hasSpec) return false
        return !hintAlreadyShown
    }
}

