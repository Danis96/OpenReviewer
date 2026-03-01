package com.example.open.reviewer.architecture.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class LightweightSignalExtractorTest {
    @Test
    fun `extracts quick android and flutter signals in fast mode`() {
        withTempRepo { root ->
            write(root, "android/app/src/main/AndroidManifest.xml", "<manifest/>")
            write(root, "build.gradle.kts", "plugins {}")
            write(root, "settings.gradle.kts", "rootProject.name = \"demo\"")
            write(
                root,
                "android/app/src/main/java/com/acme/MyApplication.kt",
                """
                @HiltAndroidApp
                class MyApplication
                """.trimIndent(),
            )
            write(
                root,
                "android/app/src/main/java/com/acme/HomeViewModel.kt",
                """
                class HomeViewModel : ViewModel()
                @Composable fun Preview() {}
                """.trimIndent(),
            )
            write(
                root,
                "android/app/src/main/java/com/acme/di/AppModule.kt",
                """
                @Module
                @InstallIn(SingletonComponent::class)
                interface AppModule {
                    @Provides fun api(): Api
                    @GET("users") fun users()
                    @Dao interface UserDao
                }
                """.trimIndent(),
            )

            write(
                root,
                "pubspec.yaml",
                """
                dependencies:
                  provider: ^6.0.0
                  flutter_riverpod: ^2.0.0
                  flutter_bloc: ^8.0.0
                  get_it: ^7.0.0
                dev_dependencies:
                  freezed: ^2.0.0
                  json_serializable: ^6.0.0
                """.trimIndent(),
            )
            write(root, "lib/main.dart", "void main() { runApp(const App()); }")
            write(
                root,
                "lib/features/auth/presentation/auth_provider.dart",
                """
                import 'package:flutter_riverpod/flutter_riverpod.dart';
                class AuthProvider : ChangeNotifier {}
                final getIt = GetIt.instance;
                """.trimIndent(),
            )
            write(
                root,
                "lib/features/home/presentation/home_bloc.dart",
                """
                import 'package:flutter_bloc/flutter_bloc.dart';
                class HomeBloc : Bloc<HomeEvent, HomeState> {}
                """.trimIndent(),
            )
            write(
                root,
                "lib/features/profile/presentation/profile_notifier.dart",
                """
                @freezed
                @JsonSerializable()
                class ProfileCubit : Cubit<int> {}
                """.trimIndent(),
            )

            val highSignal = HighSignalFileLocator().locate(root)
            val result = LightweightSignalExtractor().extract(root, highSignal)

            assertEquals(SignalExtractionMode.FAST, result.extractionMode)
            assertEquals(0.6, result.confidence, 0.0)
            assertTrue(result.android.viewModelPresenceCount >= 1)
            assertTrue(result.android.hiltAnnotationCount >= 3)
            assertTrue(result.android.roomAnnotationCount >= 1)
            assertTrue(result.android.retrofitAnnotationCount >= 1)
            assertTrue(result.android.composeUsageCount >= 1)
            assertTrue(result.flutter.providerRiverpodBlocDependencyCount >= 3)
            assertTrue(result.flutter.stateClassCount >= 3)
            assertTrue(result.flutter.runAppRootCount >= 1)
            assertTrue(result.flutter.getItUsageCount >= 1)
            assertTrue(result.flutter.freezedJsonSerializableCount >= 2)
            assertTrue(result.scannedFiles > 0)
        }
    }

    @Test
    fun `never crashes on malformed file content`() {
        withTempRepo { root ->
            write(root, "android/app/src/main/AndroidManifest.xml", "<manifest/>")
            write(root, "build.gradle.kts", "plugins {}")
            write(root, "settings.gradle.kts", "rootProject.name = \"broken\"")
            write(root, "pubspec.yaml", "name: broken")
            write(root, "lib/main.dart", "void main() { runApp(App()); }")
            write(root, "android/app/src/main/java/com/acme/BrokenApplication.kt", "class BrokenApplication")

            val brokenPath = root.resolve("android/app/src/main/java/com/acme/di/BrokenModule.kt")
            brokenPath.parent.createDirectories()
            brokenPath.writeBytes(byteArrayOf(0xC3.toByte(), 0x28.toByte(), 0x00.toByte(), 0xFF.toByte()))

            val highSignal = HighSignalFileLocator().locate(root)
            val result = LightweightSignalExtractor().extract(root, highSignal)

            assertEquals(SignalExtractionMode.FAST, result.extractionMode)
            assertEquals(0.6, result.confidence, 0.0)
            assertTrue(result.scannedFiles > 0)
        }
    }

    private fun withTempRepo(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("lightweight-signal-extractor-test")
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
