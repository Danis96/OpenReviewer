package com.example.open.reviewer.commitchecklist.vcs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitRemoteResolutionTest {
    @Test
    fun `parser keeps fetch URLs and deduplicates push entries`() {
        val output =
            """
            origin	git@github.com:org/repo.git (fetch)
            origin	git@github.com:org/repo.git (push)
            upstream	https://gitlab.company.com/group/repo.git (fetch)
            upstream	https://gitlab.company.com/group/repo.git (push)
            """.trimIndent()

        val parsed = GitRemoteParser.parse(output)

        assertEquals(2, parsed.size)
        assertEquals("origin", parsed[0].name)
        assertEquals("git@github.com:org/repo.git", parsed[0].url)
        assertEquals("upstream", parsed[1].name)
        assertEquals("https://gitlab.company.com/group/repo.git", parsed[1].url)
    }

    @Test
    fun `parser falls back to non fetch rows when needed`() {
        val output = "origin\thttps://github.com/org/repo.git"

        val parsed = GitRemoteParser.parse(output)

        assertEquals(1, parsed.size)
        assertEquals("origin", parsed[0].name)
        assertEquals("https://github.com/org/repo.git", parsed[0].url)
    }

    @Test
    fun `selector prefers origin remote`() {
        val remotes =
            listOf(
                GitRemote(name = "upstream", url = "https://gitlab.company.com/group/repo.git"),
                GitRemote(name = "origin", url = "git@github.com:org/repo.git"),
            )

        val selected = GitRemoteSelector.selectPrimary(remotes)

        assertEquals("origin", selected?.name)
        assertEquals("git@github.com:org/repo.git", selected?.url)
    }

    @Test
    fun `selector falls back to first remote when origin missing`() {
        val remotes =
            listOf(
                GitRemote(name = "company", url = "https://gitlab.company.com/group/repo.git"),
                GitRemote(name = "backup", url = "git@github.com:org/repo.git"),
            )

        val selected = GitRemoteSelector.selectPrimary(remotes)

        assertEquals("company", selected?.name)
    }

    @Test
    fun `selector returns null for empty list`() {
        val selected = GitRemoteSelector.selectPrimary(emptyList())

        assertNull(selected)
    }
}
