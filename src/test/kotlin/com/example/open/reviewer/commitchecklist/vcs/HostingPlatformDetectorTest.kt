package com.example.open.reviewer.commitchecklist.vcs

import org.junit.Assert.assertEquals
import org.junit.Test

class HostingPlatformDetectorTest {
    @Test
    fun `detects github from ssh remote`() {
        val platform =
            HostingPlatformDetector.detect(
                remoteUrl = "git@github.com:org/repo.git",
                hasGithubDirectory = false,
                hasGitlabDirectory = false,
            )

        assertEquals(HostingPlatform.GITHUB, platform)
    }

    @Test
    fun `detects github from https remote`() {
        val platform =
            HostingPlatformDetector.detect(
                remoteUrl = "https://github.com/org/repo.git",
                hasGithubDirectory = false,
                hasGitlabDirectory = false,
            )

        assertEquals(HostingPlatform.GITHUB, platform)
    }

    @Test
    fun `detects gitlab saas from remote`() {
        val platform =
            HostingPlatformDetector.detect(
                remoteUrl = "https://gitlab.com/group/repo.git",
                hasGithubDirectory = false,
                hasGitlabDirectory = false,
            )

        assertEquals(HostingPlatform.GITLAB, platform)
    }

    @Test
    fun `detects self hosted gitlab from remote`() {
        val platform =
            HostingPlatformDetector.detect(
                remoteUrl = "https://gitlab.company.com/group/repo.git",
                hasGithubDirectory = false,
                hasGitlabDirectory = false,
            )

        assertEquals(HostingPlatform.GITLAB, platform)
    }

    @Test
    fun `uses github directory heuristic when remote is unknown`() {
        val platform =
            HostingPlatformDetector.detect(
                remoteUrl = "ssh://git@bitbucket.org/org/repo.git",
                hasGithubDirectory = true,
                hasGitlabDirectory = false,
            )

        assertEquals(HostingPlatform.GITHUB, platform)
    }

    @Test
    fun `uses gitlab directory heuristic when remote is unknown`() {
        val platform =
            HostingPlatformDetector.detect(
                remoteUrl = "ssh://git@bitbucket.org/org/repo.git",
                hasGithubDirectory = false,
                hasGitlabDirectory = true,
            )

        assertEquals(HostingPlatform.GITLAB, platform)
    }

    @Test
    fun `returns unknown when remote and heuristics are inconclusive`() {
        val platform =
            HostingPlatformDetector.detect(
                remoteUrl = "ssh://git@bitbucket.org/org/repo.git",
                hasGithubDirectory = false,
                hasGitlabDirectory = false,
            )

        assertEquals(HostingPlatform.UNKNOWN, platform)
    }
}
