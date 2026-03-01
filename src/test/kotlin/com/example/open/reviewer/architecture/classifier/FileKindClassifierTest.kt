package com.example.open.reviewer.architecture.classifier

import com.example.open.reviewer.architecture.analysis.extractors.AndroidPsiSignal
import com.example.open.reviewer.architecture.analysis.extractors.DartSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKindClassifierTest {
    private val classifier = FileKindClassifier()

    @Test
    fun `classifies representative samples with high deterministic accuracy`() {
        val cases =
            listOf(
                FileKindInput(
                    path = "/tmp/app/src/main/java/com/acme/ui/MainActivity.kt",
                    androidSignals = setOf(AndroidPsiSignal.ANDROID_ACTIVITY),
                    supertypes = setOf("ComponentActivity"),
                ) to FileKind.UI,
                FileKindInput(
                    path = "/tmp/app/lib/features/home/home_bloc.dart",
                    dartSignals = setOf(DartSignal.FLUTTER_BLOC),
                ) to FileKind.STATE,
                FileKindInput(
                    path = "/tmp/app/src/main/java/com/acme/data/repository/UserRepositoryImpl.kt",
                    signalTags = setOf("repository"),
                ) to FileKind.REPOSITORY,
                FileKindInput(
                    path = "/tmp/app/src/main/java/com/acme/domain/usecase/SyncServiceUseCase.kt",
                    declarationNames = setOf("SyncServiceUseCase"),
                ) to FileKind.SERVICE,
                FileKindInput(
                    path = "/tmp/app/src/main/java/com/acme/data/remote/UserRemoteDataSource.kt",
                    androidSignals = setOf(AndroidPsiSignal.ANDROID_RETROFIT),
                ) to FileKind.DATA_SOURCE,
                FileKindInput(
                    path = "/tmp/app/lib/model/user_dto.dart",
                    dartSignals = setOf(DartSignal.FLUTTER_JSON_SERIALIZABLE, DartSignal.FLUTTER_FREEZED),
                ) to FileKind.MODEL,
                FileKindInput(
                    path = "/tmp/app/src/main/java/com/acme/di/AppModule.kt",
                    androidSignals = setOf(AndroidPsiSignal.ANDROID_HILT),
                ) to FileKind.DI,
                FileKindInput(
                    path = "/tmp/app/settings.gradle.kts",
                ) to FileKind.CONFIG,
                FileKindInput(
                    path = "/tmp/app/src/test/java/com/acme/UserRepositoryTest.kt",
                ) to FileKind.TEST,
                FileKindInput(
                    path = "/tmp/app/scripts/misc.txt",
                ) to FileKind.OTHER,
            )

        cases.forEach { (input, expected) ->
            val result = classifier.classify(input)
            assertEquals("Failed for ${input.path}", expected, result.kind)
            if (expected != FileKind.OTHER) {
                assertTrue(result.score > 0)
            }
        }
    }

    @Test
    fun `produces deterministic summary counts and samples`() {
        val summary =
            classifier.classifyAll(
                listOf(
                    FileKindInput(path = "/tmp/app/lib/ui/home_page.dart", dartSignals = setOf(DartSignal.FLUTTER_WIDGET)),
                    FileKindInput(path = "/tmp/app/lib/state/home_provider.dart", dartSignals = setOf(DartSignal.FLUTTER_PROVIDER)),
                    FileKindInput(path = "/tmp/app/src/main/java/com/acme/di/AppModule.kt", androidSignals = setOf(AndroidPsiSignal.ANDROID_HILT)),
                ),
            )
        assertTrue(summary.counts[FileKind.UI] == 1)
        assertTrue(summary.counts[FileKind.STATE] == 1)
        assertTrue(summary.counts[FileKind.DI] == 1)
        assertTrue(summary.samples[FileKind.UI].orEmpty().isNotEmpty())
    }
}
