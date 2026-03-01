package com.example.open.reviewer.architecture.classifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PurposeLineGeneratorTest {
    private val generator = PurposeLineGenerator()

    @Test
    fun `uses template-based deterministic lines for core kinds`() {
        assertEquals(
            "UI screen/widget for home screen.",
            generator.generate(FileKind.UI, "/tmp/app/lib/ui/HomeScreen.dart"),
        )
        assertEquals(
            "State holder for home view model.",
            generator.generate(FileKind.STATE, "/tmp/app/src/main/java/com/acme/HomeViewModel.kt"),
        )
        assertEquals(
            "Data access for user repository.",
            generator.generate(FileKind.REPOSITORY, "/tmp/app/src/main/java/com/acme/UserRepository.kt"),
        )
        assertEquals(
            "Dependency injection wiring for app module.",
            generator.generate(FileKind.DI, "/tmp/app/src/main/java/com/acme/di/AppModule.kt"),
        )
    }

    @Test
    fun `keeps purpose line human-readable and within 300 chars`() {
        val longPath = "/tmp/" + "A".repeat(600) + ".kt"
        val line = generator.generate(FileKind.SERVICE, longPath)

        assertTrue(line.length <= 300)
        assertTrue(line.startsWith("Service logic for "))
        assertTrue(line.endsWith(".") || line.endsWith("…"))
    }
}
