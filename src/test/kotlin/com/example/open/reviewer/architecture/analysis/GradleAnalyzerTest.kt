package com.example.open.reviewer.architecture.analysis

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class GradleAnalyzerTest {
    @Test
    fun `detects plugins dependencies modules and android stack`() {
        withTempRepo { root ->
            val settings =
                write(
                    root,
                    "settings.gradle.kts",
                    """
                    include(":app", ":feature:home", ":core:data")
                    """.trimIndent(),
                )
            val build =
                write(
                    root,
                    "app/build.gradle.kts",
                    """
                    plugins {
                        id("com.android.application")
                        id("org.jetbrains.kotlin.android")
                        id("com.google.dagger.hilt.android")
                    }

                    dependencies {
                        implementation("com.squareup.retrofit2:retrofit:2.11.0")
                        implementation("com.squareup.okhttp3:okhttp:4.12.0")
                        implementation("androidx.room:room-ktx:2.6.1")
                        implementation("androidx.compose.ui:ui:1.7.0")
                        kapt("com.google.dagger:hilt-android-compiler:2.52")
                    }
                    """.trimIndent(),
                )

            val result = GradleAnalyzer().analyze(listOf(settings.toString(), build.toString()))
            assertTrue(result.plugins.contains("com.android.application"))
            assertTrue(result.plugins.contains("org.jetbrains.kotlin.android"))
            assertTrue(result.dependencies.any { it.contains("retrofit2:retrofit") })
            assertTrue(result.dependencies.any { it.contains("androidx.room:room-ktx") })
            assertTrue(result.modules.contains(":app"))
            assertTrue(result.modules.contains(":feature:home"))
            assertTrue(result.modules.contains(":core:data"))
            assertTrue(result.androidStackSignals.contains("ANDROID_GRADLE_PLUGIN"))
            assertTrue(result.androidStackSignals.contains("KOTLIN_ANDROID"))
            assertTrue(result.androidStackSignals.contains("HILT"))
            assertTrue(result.androidStackSignals.contains("ROOM"))
            assertTrue(result.androidStackSignals.contains("RETROFIT"))
            assertTrue(result.androidStackSignals.contains("COMPOSE"))
            assertTrue(result.hasAndroidStack())
        }
    }

    private fun withTempRepo(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("gradle-analyzer-test")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun write(
        root: Path,
        relativePath: String,
        content: String,
    ): Path {
        val file = root.resolve(relativePath)
        file.parent?.createDirectories()
        file.writeText(content)
        return file
    }
}
