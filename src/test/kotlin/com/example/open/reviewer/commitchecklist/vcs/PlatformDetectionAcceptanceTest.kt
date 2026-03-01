package com.example.open.reviewer.commitchecklist.vcs

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformDetectionAcceptanceTest {
    @Test
    fun `github ssh remote resolves to github`() {
        val result =
            HostingPlatformDetector.detect(
                remoteUrl = "git@github.com:org/repo.git",
                hasGithubDirectory = false,
                hasGitlabDirectory = false,
            )

        assertEquals(HostingPlatform.GITHUB, result)
    }

    @Test
    fun `github https remote resolves to github`() {
        val result =
            HostingPlatformDetector.detect(
                remoteUrl = "https://github.com/org/repo.git",
                hasGithubDirectory = false,
                hasGitlabDirectory = false,
            )

        assertEquals(HostingPlatform.GITHUB, result)
    }

    @Test
    fun `gitlab saas remote resolves to gitlab`() {
        val result =
            HostingPlatformDetector.detect(
                remoteUrl = "https://gitlab.com/group/repo.git",
                hasGithubDirectory = false,
                hasGitlabDirectory = false,
            )

        assertEquals(HostingPlatform.GITLAB, result)
    }

    @Test
    fun `gitlab self hosted remote resolves to gitlab`() {
        val result =
            HostingPlatformDetector.detect(
                remoteUrl = "https://gitlab.company.com/group/repo.git",
                hasGithubDirectory = false,
                hasGitlabDirectory = false,
            )

        assertEquals(HostingPlatform.GITLAB, result)
    }

    @Test
    fun `unknown remote resolves to unknown without heuristics`() {
        val result =
            HostingPlatformDetector.detect(
                remoteUrl = "ssh://git@bitbucket.org/org/repo.git",
                hasGithubDirectory = false,
                hasGitlabDirectory = false,
            )

        assertEquals(HostingPlatform.UNKNOWN, result)
    }
}
