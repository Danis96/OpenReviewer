package com.example.open.reviewer.commitchecklist.vcs

import java.nio.file.Path

internal object GitRootSelector {
    fun selectPreferredRoot(
        roots: List<Path>,
        projectPath: Path?,
    ): Path? {
        if (roots.isEmpty()) return null

        val normalizedRoots = roots.map { it.toAbsolutePath().normalize() }
        val normalizedProjectPath = projectPath?.toAbsolutePath()?.normalize()

        if (normalizedProjectPath != null) {
            val containingRoots = normalizedRoots.filter { normalizedProjectPath.startsWith(it) }
            if (containingRoots.isNotEmpty()) {
                return containingRoots.maxByOrNull { it.nameCount }
            }
        }

        return normalizedRoots
            .sortedBy { it.toString() }
            .firstOrNull()
    }
}
