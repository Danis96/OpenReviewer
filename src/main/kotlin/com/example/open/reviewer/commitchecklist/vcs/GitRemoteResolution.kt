package com.example.open.reviewer.commitchecklist.vcs

data class GitRemote(
    val name: String,
    val url: String,
)

internal object GitRemoteParser {
    private val remoteLinePattern = Regex("""^(\S+)\s+(\S+)\s*(?:\((fetch|push)\))?\s*$""")

    fun parse(gitRemoteVerboseOutput: String): List<GitRemote> {
        if (gitRemoteVerboseOutput.isBlank()) return emptyList()

        val fetchEntries = linkedMapOf<String, GitRemote>()
        val fallbackEntries = linkedMapOf<String, GitRemote>()

        gitRemoteVerboseOutput
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val match = remoteLinePattern.matchEntire(line) ?: return@forEach
                val remoteName = match.groupValues[1]
                val remoteUrl = match.groupValues[2]
                val mode = match.groupValues[3]
                val entry = GitRemote(name = remoteName, url = remoteUrl)

                if (mode.equals("fetch", ignoreCase = true)) {
                    fetchEntries.putIfAbsent(remoteName, entry)
                } else {
                    fallbackEntries.putIfAbsent(remoteName, entry)
                }
            }

        if (fetchEntries.isNotEmpty()) return fetchEntries.values.toList()
        return fallbackEntries.values.toList()
    }
}

internal object GitRemoteSelector {
    fun selectPrimary(remotes: List<GitRemote>): GitRemote? {
        if (remotes.isEmpty()) return null
        return remotes.firstOrNull { it.name == ORIGIN_REMOTE_NAME } ?: remotes.first()
    }

    private const val ORIGIN_REMOTE_NAME = "origin"
}

