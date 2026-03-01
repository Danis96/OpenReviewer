package com.example.open.reviewer.architecture.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternNormalizerTest {
    private val normalizer = PatternNormalizer()

    @Test
    fun `merges duplicates and cleans names`() {
        val normalized =
            normalizer.normalize(
                listOf(
                    ArchitectureDetectedPattern("mvvm", 0.72, listOf("a.kt")),
                    ArchitectureDetectedPattern("MVVM architecture", 0.81, listOf("b.kt")),
                    ArchitectureDetectedPattern("clean-architecture", 0.65, listOf("c.kt")),
                    ArchitectureDetectedPattern("clean arch", 0.60, listOf("d.kt")),
                ),
            )

        assertEquals(2, normalized.size)
        assertEquals("MVVM", normalized[0].name)
        assertEquals(0.81, normalized[0].confidence, 0.0001)
        assertTrue(normalized[0].evidencePaths.contains("a.kt"))
        assertTrue(normalized[0].evidencePaths.contains("b.kt"))
        assertEquals("Clean Architecture", normalized[1].name)
    }
}
