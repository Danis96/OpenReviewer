package com.example.open.reviewer.architecture.analysis

import com.example.open.reviewer.architecture.model.ArchitecturePattern
import com.example.open.reviewer.architecture.scanner.HighSignalFileLocator
import com.example.open.reviewer.architecture.scanner.LightweightSignalExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class PreliminaryArchitectureScorerTest {
    @Test
    fun `produces preliminary mvvm and clean confidence from local heuristics`() {
        withTempRepo { root ->
            write(root, "android/app/src/main/AndroidManifest.xml", "<manifest/>")
            write(root, "build.gradle.kts", "plugins {}")
            write(root, "settings.gradle.kts", "rootProject.name = \"demo\"")
            write(root, "app/src/main/java/com/acme/domain/auth/LoginUseCase.kt", "class LoginUseCase")
            write(root, "app/src/main/java/com/acme/data/repository/AuthRepositoryImpl.kt", "class AuthRepositoryImpl : AuthRepository")
            write(root, "app/src/main/java/com/acme/domain/repository/AuthRepository.kt", "interface AuthRepository")
            write(
                root,
                "app/src/main/java/com/acme/presentation/login/LoginScreen.kt",
                "class LoginScreen { val vm = LoginViewModel() }",
            )
            write(root, "app/src/main/java/com/acme/presentation/login/LoginViewModel.kt", "class LoginViewModel : ViewModel()")

            val highSignal = HighSignalFileLocator().locate(root)
            val fast = LightweightSignalExtractor().extract(root, highSignal)
            val guess = PreliminaryArchitectureScorer().score(root, highSignal, fast)

            assertTrue(guess.isPreliminary)
            assertNotNull(guess.topPattern)
            val mvvm = guess.patternConfidences.first { it.pattern == ArchitecturePattern.MVVM }
            val clean = guess.patternConfidences.first { it.pattern == ArchitecturePattern.CLEAN_ARCHITECTURE }
            assertTrue(mvvm.confidence > 0.0)
            assertTrue(clean.confidence > 0.0)
        }
    }

    @Test
    fun `scores flutter bloc above provider and riverpod when bloc signals dominate`() {
        withTempRepo { root ->
            write(root, "pubspec.yaml", "dependencies:\n  flutter_bloc: ^8.1.0")
            write(root, "lib/main.dart", "void main() { runApp(App()); }")
            write(
                root,
                "lib/features/home/presentation/home_bloc.dart",
                """
                import 'package:flutter_bloc/flutter_bloc.dart';
                class HomeBloc : Bloc<HomeEvent, HomeState> {}
                class HomeCubit : Cubit<int> {}
                """.trimIndent(),
            )

            val highSignal = HighSignalFileLocator().locate(root)
            val fast = LightweightSignalExtractor().extract(root, highSignal)
            val guess = PreliminaryArchitectureScorer().score(root, highSignal, fast)

            val bloc = guess.patternConfidences.first { it.pattern == ArchitecturePattern.FLUTTER_BLOC }
            val provider = guess.patternConfidences.first { it.pattern == ArchitecturePattern.FLUTTER_PROVIDER }
            val riverpod = guess.patternConfidences.first { it.pattern == ArchitecturePattern.FLUTTER_RIVERPOD }

            assertTrue(bloc.confidence > provider.confidence)
            assertTrue(bloc.confidence >= riverpod.confidence)
            assertEquals(
                ArchitecturePattern.FLUTTER_BLOC,
                guess.patternConfidences.first().pattern,
            )
        }
    }

    @Test
    fun `all pattern confidences stay within 0 to 1`() {
        withTempRepo { root ->
            write(root, "pubspec.yaml", "name: sample")
            write(root, "lib/main.dart", "void main() { runApp(App()); }")

            val highSignal = HighSignalFileLocator().locate(root)
            val fast = LightweightSignalExtractor().extract(root, highSignal)
            val guess = PreliminaryArchitectureScorer().score(root, highSignal, fast)

            assertTrue(guess.patternConfidences.isNotEmpty())
            guess.patternConfidences.forEach { item ->
                assertTrue(item.confidence in 0.0..1.0)
                assertTrue(item.score in 0..item.maxScore)
            }
        }
    }

    private fun withTempRepo(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("preliminary-architecture-scorer-test")
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
