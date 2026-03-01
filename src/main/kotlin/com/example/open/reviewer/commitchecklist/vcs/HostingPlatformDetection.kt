package com.example.open.reviewer.commitchecklist.vcs

enum class HostingPlatform {
    GITHUB,
    GITLAB,
    UNKNOWN,
}

internal object HostingPlatformDetector {
    fun detect(
        remoteUrl: String?,
        hasGithubDirectory: Boolean,
        hasGitlabDirectory: Boolean,
    ): HostingPlatform {
        val normalizedRemote = remoteUrl?.trim()?.lowercase().orEmpty()

        if ("github.com" in normalizedRemote) return HostingPlatform.GITHUB
        if ("gitlab" in normalizedRemote) return HostingPlatform.GITLAB

        if (hasGithubDirectory && !hasGitlabDirectory) return HostingPlatform.GITHUB
        if (hasGitlabDirectory && !hasGithubDirectory) return HostingPlatform.GITLAB

        return HostingPlatform.UNKNOWN
    }
}

