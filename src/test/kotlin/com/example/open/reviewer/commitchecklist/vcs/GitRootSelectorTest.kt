package com.example.open.reviewer.commitchecklist.vcs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

class GitRootSelectorTest {
    @Test
    fun `returns null when no roots are available`() {
        val selected = GitRootSelector.selectPreferredRoot(emptyList(), Path.of("/repo/app"))

        assertNull(selected)
    }

    @Test
    fun `returns the only root for single-root projects`() {
        val selected = GitRootSelector.selectPreferredRoot(listOf(Path.of("/repo")), Path.of("/repo"))

        assertEquals(Path.of("/repo").toAbsolutePath().normalize(), selected)
    }

    @Test
    fun `prefers root that contains opened project path`() {
        val selected =
            GitRootSelector.selectPreferredRoot(
                roots = listOf(Path.of("/other"), Path.of("/repo")),
                projectPath = Path.of("/repo/apps/mobile"),
            )

        assertEquals(Path.of("/repo").toAbsolutePath().normalize(), selected)
    }

    @Test
    fun `prefers deepest containing root when roots are nested`() {
        val selected =
            GitRootSelector.selectPreferredRoot(
                roots = listOf(Path.of("/repo"), Path.of("/repo/apps/mobile")),
                projectPath = Path.of("/repo/apps/mobile/module"),
            )

        assertEquals(Path.of("/repo/apps/mobile").toAbsolutePath().normalize(), selected)
    }

    @Test
    fun `falls back deterministically when project path does not match any root`() {
        val selected =
            GitRootSelector.selectPreferredRoot(
                roots = listOf(Path.of("/z-root"), Path.of("/a-root")),
                projectPath = Path.of("/workspace"),
            )

        assertEquals(Path.of("/a-root").toAbsolutePath().normalize(), selected)
    }
}
