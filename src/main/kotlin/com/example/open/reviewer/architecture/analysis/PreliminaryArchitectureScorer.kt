package com.example.open.reviewer.architecture.analysis

import com.example.open.reviewer.architecture.model.ArchitecturePattern
import com.example.open.reviewer.architecture.model.PatternConfidence
import com.example.open.reviewer.architecture.model.PreliminaryArchitectureGuess
import com.example.open.reviewer.architecture.scanner.FastSignalExtractionResult
import com.example.open.reviewer.architecture.scanner.HighSignalScanResult
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class PreliminaryArchitectureScorer {
    fun score(
        root: Path,
        highSignal: HighSignalScanResult,
        fastSignals: FastSignalExtractionResult,
    ): PreliminaryArchitectureGuess {
        val files = highSignal.highSignalFiles().map { Path.of(it) }.filter { Files.isRegularFile(it) }
        val fileTexts = files.associateWith { readTextSafe(it).orEmpty() }
        val normalizedPaths = files.map { normalizePath(it) }

        val viewModelNames =
            highSignal.topViewModelFiles
                .map { Path.of(it).fileName.toString().removeSuffix(".kt").removeSuffix(".java") }
                .filter { it.isNotBlank() }
                .toSet()

        val uiFiles =
            files.filter { file ->
                val p = normalizePath(file).lowercase()
                p.contains("/ui/") || p.contains("/presentation/") || UI_FILE_NAME_REGEX.containsMatchIn(file.fileName.toString())
            }
        val uiToViewModelEdges =
            uiFiles.sumOf { file ->
                val content = fileTexts[file].orEmpty()
                viewModelNames.count { name -> Regex("\\b$name\\b").containsMatchIn(content) }
            }

        val repositorySignalCount =
            files.count { file ->
                val lowerPath = normalizePath(file).lowercase()
                lowerPath.contains("repository")
            } + fileTexts.values.sumOf { text -> REPOSITORY_TOKEN_REGEX.findAll(text).count() }

        val mvvmScore = (fastSignals.android.viewModelPresenceCount * 12).coerceAtMost(40) +
            (uiToViewModelEdges * 15).coerceAtMost(35) +
            (repositorySignalCount * 2).coerceAtMost(25)
        val mvvmSignals =
            listOf(
                "ViewModel presence: ${fastSignals.android.viewModelPresenceCount}",
                "UI->ViewModel edges: $uiToViewModelEdges",
                "Repository signals: $repositorySignalCount",
            )

        val hasDomain = normalizedPaths.any { it.contains("/domain/") }
        val hasData = normalizedPaths.any { it.contains("/data/") }
        val hasPresentation = normalizedPaths.any { it.contains("/presentation/") || it.contains("/ui/") }
        val layerScore = listOf(hasDomain, hasData, hasPresentation).count { it } * 20

        val useCaseCount =
            files.count { file ->
                val fileName = file.fileName.toString()
                fileName.contains("UseCase")
            } + fileTexts.values.sumOf { text -> USE_CASE_REGEX.findAll(text).count() }

        val repositoryInterfaces = files.count { fileTexts[it].orEmpty().let(INTERFACE_REPOSITORY_REGEX::containsMatchIn) }
        val repositoryImplementations =
            files.count { file ->
                val text = fileTexts[file].orEmpty()
                val lowerPath = normalizePath(file).lowercase()
                IMPLEMENTATION_REPOSITORY_REGEX.containsMatchIn(text) ||
                    lowerPath.contains("repositoryimpl") ||
                    lowerPath.contains("defaultrepository")
            }
        val interfaceImplSplitBonus = if (repositoryInterfaces > 0 && repositoryImplementations > 0) 20 else 0

        val cleanScore =
            layerScore +
                (useCaseCount * 5).coerceAtMost(20) +
                interfaceImplSplitBonus
        val cleanSignals =
            listOf(
                "Layer folders domain/data/presentation: ${listOf(hasDomain, hasData, hasPresentation).count { it }}/3",
                "UseCase signals: $useCaseCount",
                "Repository interface+impl split: ${if (interfaceImplSplitBonus > 0) "yes" else "no"}",
            )

        val providerMentions = fileTexts.values.sumOf { PROVIDER_REGEX.findAll(it).count() }
        val riverpodMentions = fileTexts.values.sumOf { RIVERPOD_REGEX.findAll(it).count() }
        val blocMentions = fileTexts.values.sumOf { BLOC_REGEX.findAll(it).count() }

        val changeNotifierClasses = fileTexts.values.sumOf { CHANGE_NOTIFIER_CLASS_REGEX.findAll(it).count() }
        val blocClasses = fileTexts.values.sumOf { BLOC_CLASS_REGEX.findAll(it).count() }
        val riverpodClasses = fileTexts.values.sumOf { RIVERPOD_STATE_CLASS_REGEX.findAll(it).count() }

        val providerScore =
            (providerMentions * 8).coerceAtMost(70) +
                (changeNotifierClasses * 10).coerceAtMost(30)
        val blocScore =
            (blocMentions * 8).coerceAtMost(70) +
                (blocClasses * 10).coerceAtMost(30)
        val riverpodScore =
            (riverpodMentions * 8).coerceAtMost(70) +
                (riverpodClasses * 10).coerceAtMost(30)

        val patternConfidences =
            listOf(
                patternConfidence(ArchitecturePattern.MVVM, mvvmScore, mvvmSignals),
                patternConfidence(ArchitecturePattern.CLEAN_ARCHITECTURE, cleanScore, cleanSignals),
                patternConfidence(
                    ArchitecturePattern.FLUTTER_PROVIDER,
                    providerScore,
                    listOf("Provider signals: $providerMentions", "ChangeNotifier classes: $changeNotifierClasses"),
                ),
                patternConfidence(
                    ArchitecturePattern.FLUTTER_BLOC,
                    blocScore,
                    listOf("Bloc signals: $blocMentions", "Bloc/Cubit classes: $blocClasses"),
                ),
                patternConfidence(
                    ArchitecturePattern.FLUTTER_RIVERPOD,
                    riverpodScore,
                    listOf("Riverpod signals: $riverpodMentions", "Riverpod state classes: $riverpodClasses"),
                ),
            ).sortedWith(compareByDescending<PatternConfidence> { it.confidence }.thenBy { it.pattern.name })

        val top = patternConfidences.firstOrNull()
        return PreliminaryArchitectureGuess(
            isPreliminary = true,
            topPattern = top?.pattern,
            topConfidence = top?.confidence ?: 0.0,
            patternConfidences = patternConfidences,
        )
    }

    private fun patternConfidence(
        pattern: ArchitecturePattern,
        score: Int,
        signals: List<String>,
    ): PatternConfidence {
        val bounded = score.coerceIn(0, 100)
        return PatternConfidence(
            pattern = pattern,
            confidence = bounded / 100.0,
            score = bounded,
            maxScore = 100,
            signals = signals,
        )
    }

    private fun readTextSafe(path: Path): String? {
        return try {
            String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun normalizePath(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/')

    companion object {
        private val UI_FILE_NAME_REGEX = Regex("(Activity|Fragment|Screen|Page|View|Widget)\\.(kt|java|dart)$")
        private val REPOSITORY_TOKEN_REGEX = Regex("\\bRepository\\b")
        private val USE_CASE_REGEX = Regex("\\bclass\\s+[A-Za-z_][A-Za-z0-9_]*UseCase\\b|\\bUseCase\\b")
        private val INTERFACE_REPOSITORY_REGEX = Regex("\\binterface\\s+[A-Za-z_][A-Za-z0-9_]*Repository\\b")
        private val IMPLEMENTATION_REPOSITORY_REGEX = Regex("\\bclass\\s+[A-Za-z_][A-Za-z0-9_]*(RepositoryImpl|Default[A-Za-z0-9_]*Repository)\\b")

        private val PROVIDER_REGEX = Regex("\\bprovider\\b|ChangeNotifierProvider|ProviderScope")
        private val RIVERPOD_REGEX = Regex("\\briverpod\\b|flutter_riverpod|StateNotifierProvider|ConsumerWidget|WidgetRef\\b")
        private val BLOC_REGEX = Regex("\\bflutter_bloc\\b|\\bBloc\\b|\\bCubit\\b|BlocBuilder|BlocProvider")

        private val CHANGE_NOTIFIER_CLASS_REGEX = Regex("\\bclass\\s+[A-Za-z_][A-Za-z0-9_]*\\s*:\\s*ChangeNotifier\\b")
        private val BLOC_CLASS_REGEX = Regex("\\bclass\\s+[A-Za-z_][A-Za-z0-9_]*\\s*:\\s*(Bloc<|Cubit<)")
        private val RIVERPOD_STATE_CLASS_REGEX = Regex("\\bclass\\s+[A-Za-z_][A-Za-z0-9_]*(Notifier|StateNotifier)\\b")
    }
}
