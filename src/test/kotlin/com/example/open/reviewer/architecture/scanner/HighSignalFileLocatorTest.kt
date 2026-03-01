package com.example.open.reviewer.architecture.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class HighSignalFileLocatorTest {
    @Test
    fun `finds high-signal files in mixed android and flutter repo`() {
        withTempRepo { root ->
            write(root, "android/app/src/main/AndroidManifest.xml", "<manifest />")
            write(root, "build.gradle.kts", "plugins {}")
            write(root, "android/build.gradle", "apply plugin")
            write(root, "settings.gradle", "include(':app')")
            write(root, "android/app/src/main/java/com/acme/MyApplication.kt", "class MyApplication")
            write(root, "android/app/src/main/java/com/acme/HomeViewModel.kt", "class HomeViewModel")
            write(root, "android/app/src/main/java/com/acme/LoginViewModel.kt", "class LoginViewModel")
            write(root, "android/app/src/main/java/com/acme/di/AppModule.kt", "@Module class AppModule")
            write(root, "android/app/src/main/java/com/acme/di/NetworkModule.kt", "@Module class NetworkModule")

            write(root, "pubspec.yaml", "name: demo")
            write(root, "lib/main.dart", "void main() {}")
            write(root, "lib/features/auth/presentation/auth_provider.dart", "class AuthProvider {}")
            write(root, "lib/features/home/presentation/home_bloc.dart", "class HomeBloc {}")
            write(root, "lib/features/profile/presentation/profile_notifier.dart", "class ProfileNotifier {}")
            write(root, "build/generated/FakeViewModel.kt", "class FakeViewModel")
            write(root, "node_modules/pkg/NoiseModule.kt", "@Module class NoiseModule")

            val result = HighSignalFileLocator().locate(root)

            assertTrue(result.androidManifestFiles.any { it.endsWith("/android/app/src/main/AndroidManifest.xml") })
            assertTrue(result.gradleBuildFiles.any { it.endsWith("/build.gradle.kts") })
            assertTrue(result.gradleBuildFiles.any { it.endsWith("/android/build.gradle") })
            assertTrue(result.settingsGradleFiles.any { it.endsWith("/settings.gradle") })
            assertTrue(result.applicationFiles.any { it.endsWith("/MyApplication.kt") })
            assertEquals(2, result.topDiModuleFiles.size)
            assertTrue(result.flutterMainFiles.any { it.endsWith("/lib/main.dart") })
            assertTrue(result.pubspecFiles.any { it.endsWith("/pubspec.yaml") })
            assertEquals(
                listOf("features/auth", "features/home", "features/profile"),
                result.topFlutterFeatureFolders,
            )
            assertEquals(3, result.topFlutterStateFiles.size)
            assertFalse(result.topViewModelFiles.any { it.contains("/build/") })
            assertFalse(result.topDiModuleFiles.any { it.contains("/node_modules/") })
        }
    }

    @Test
    fun `enforces top-n limits and keeps output deterministic`() {
        withTempRepo { root ->
            write(root, "a/OneViewModel.kt", "class OneViewModel")
            write(root, "b/TwoViewModel.kt", "class TwoViewModel")
            write(root, "c/ThreeViewModel.kt", "class ThreeViewModel")
            write(root, "di/AOneModule.kt", "@Module class AOneModule")
            write(root, "di/BTwoModule.kt", "@Module class BTwoModule")
            write(root, "lib/main.dart", "void main() {}")
            write(root, "pubspec.yaml", "name: app")
            write(root, "lib/features/a/presentation/a_provider.dart", "class AProvider {}")
            write(root, "lib/features/b/presentation/b_bloc.dart", "class BBloc {}")

            val locator =
                HighSignalFileLocator(
                    HighSignalScanConfig(
                        topViewModelLimit = 2,
                        topDiModuleLimit = 1,
                        topFlutterStateFileLimit = 1,
                        topFlutterFeatureFolderLimit = 1,
                    ),
                )

            val first = locator.locate(root)
            val second = locator.locate(root)

            assertEquals(2, first.topViewModelFiles.size)
            assertEquals(1, first.topDiModuleFiles.size)
            assertEquals(1, first.topFlutterStateFiles.size)
            assertEquals(1, first.topFlutterFeatureFolders.size)
            assertEquals(first.topViewModelFiles, second.topViewModelFiles)
            assertEquals(first.topDiModuleFiles, second.topDiModuleFiles)
            assertEquals(first.topFlutterStateFiles, second.topFlutterStateFiles)
            assertEquals(first.topFlutterFeatureFolders, second.topFlutterFeatureFolders)
            assertEquals(first.highSignalFiles(), second.highSignalFiles())
        }
    }

    @Test
    fun `di module detection requires at-module annotation`() {
        withTempRepo { root ->
            write(root, "di/AnnotatedModule.kt", "@Module class AnnotatedModule")
            write(root, "di/PlainModule.kt", "class PlainModule")

            val result = HighSignalFileLocator().locate(root)

            assertEquals(1, result.topDiModuleFiles.size)
            assertTrue(result.topDiModuleFiles.single().endsWith("/di/AnnotatedModule.kt"))
        }
    }

    @Test
    fun `detects flutter main entrypoint variants`() {
        withTempRepo { root ->
            write(root, "lib/main.dart", "void main() {}")
            write(root, "lib/main_dev.dart", "void main() {}")
            write(root, "lib/main_prod.dart", "void main() {}")
            write(root, "lib/main_common.dart", "void main() {}")
            write(root, "lib/main-staging.dart", "void main() {}")
            write(root, "lib/not_main.dart", "void main() {}")

            val result = HighSignalFileLocator().locate(root)
            val files = result.flutterMainFiles

            assertTrue(files.any { it.endsWith("/lib/main.dart") })
            assertTrue(files.any { it.endsWith("/lib/main_dev.dart") })
            assertTrue(files.any { it.endsWith("/lib/main_prod.dart") })
            assertTrue(files.any { it.endsWith("/lib/main_common.dart") })
            assertTrue(files.any { it.endsWith("/lib/main-staging.dart") })
            assertFalse(files.any { it.endsWith("/lib/not_main.dart") })
        }
    }

    private fun withTempRepo(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("high-signal-locator-test")
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
    ) {
        val file = root.resolve(relativePath)
        file.parent?.createDirectories()
        file.writeText(content)
    }
}
