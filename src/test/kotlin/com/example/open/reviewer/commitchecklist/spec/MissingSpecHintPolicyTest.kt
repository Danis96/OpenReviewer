package com.example.open.reviewer.commitchecklist.spec

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingSpecHintPolicyTest {
    @Test
    fun `shows hint when git root exists spec is missing and hint was never shown`() {
        val shouldShow =
            MissingSpecHintPolicy.shouldShowHint(
                hasGitRoot = true,
                hasSpec = false,
                hintAlreadyShown = false,
            )

        assertTrue(shouldShow)
    }

    @Test
    fun `does not show hint when no git root exists`() {
        val shouldShow =
            MissingSpecHintPolicy.shouldShowHint(
                hasGitRoot = false,
                hasSpec = false,
                hintAlreadyShown = false,
            )

        assertFalse(shouldShow)
    }

    @Test
    fun `does not show hint when spec exists`() {
        val shouldShow =
            MissingSpecHintPolicy.shouldShowHint(
                hasGitRoot = true,
                hasSpec = true,
                hintAlreadyShown = false,
            )

        assertFalse(shouldShow)
    }

    @Test
    fun `does not show hint when it was already shown once`() {
        val shouldShow =
            MissingSpecHintPolicy.shouldShowHint(
                hasGitRoot = true,
                hasSpec = false,
                hintAlreadyShown = true,
            )

        assertFalse(shouldShow)
    }
}
