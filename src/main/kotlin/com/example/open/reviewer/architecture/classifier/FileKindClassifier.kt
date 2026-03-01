package com.example.open.reviewer.architecture.classifier

import com.example.open.reviewer.architecture.analysis.extractors.AndroidPsiSignal
import com.example.open.reviewer.architecture.analysis.extractors.DartSignal
import java.nio.file.Path
import java.util.Locale

enum class FileKind {
    UI,
    STATE,
    REPOSITORY,
    SERVICE,
    DATA_SOURCE,
    MODEL,
    DI,
    CONFIG,
    TEST,
    OTHER,
}

data class FileKindInput(
    val path: String,
    val signalTags: Set<String> = emptySet(),
    val supertypes: Set<String> = emptySet(),
    val imports: Set<String> = emptySet(),
    val declarationNames: Set<String> = emptySet(),
    val androidSignals: Set<AndroidPsiSignal> = emptySet(),
    val dartSignals: Set<DartSignal> = emptySet(),
)

data class FileKindClassification(
    val path: String,
    val kind: FileKind,
    val score: Int,
    val reasons: List<String>,
    val purposeLine: String,
)

data class FileKindClassificationSummary(
    val classifications: List<FileKindClassification>,
    val counts: Map<FileKind, Int>,
    val samples: Map<FileKind, List<String>>,
)

class FileKindClassifier {
    private val purposeLineGenerator = PurposeLineGenerator()

    fun classify(input: FileKindInput): FileKindClassification {
        val pathLower = normalize(input.path).lowercase(Locale.ROOT)
        val fileName = pathLower.substringAfterLast('/')
        val tokens = buildTokenBlob(input, pathLower, fileName)
        val scores = mutableMapOf<FileKind, Int>()
        val reasons = mutableMapOf<FileKind, MutableList<String>>()

        fun add(kind: FileKind, value: Int, reason: String) {
            scores[kind] = (scores[kind] ?: 0) + value
            reasons.getOrPut(kind) { mutableListOf() }.add(reason)
        }

        if (isTestFile(pathLower, fileName, tokens)) add(FileKind.TEST, 100, "test path/name/signal")

        if (
            "/di/" in pathLower ||
            "module" in fileName ||
            "component" in fileName ||
            "inject" in tokens ||
            AndroidPsiSignal.ANDROID_HILT in input.androidSignals
        ) {
            add(FileKind.DI, 60, "di/hilt/module hint")
        }

        if (
            "manifest" in fileName ||
            "build.gradle" in fileName ||
            "settings.gradle" in fileName ||
            "config" in fileName ||
            "settings" in fileName ||
            "/config/" in pathLower
        ) {
            add(FileKind.CONFIG, 60, "config/build/manifest hint")
        }

        if (
            AndroidPsiSignal.ANDROID_ACTIVITY in input.androidSignals ||
            AndroidPsiSignal.ANDROID_FRAGMENT in input.androidSignals ||
            AndroidPsiSignal.ANDROID_COMPOSE in input.androidSignals ||
            DartSignal.FLUTTER_WIDGET in input.dartSignals ||
            hasAny(tokens, "activity", "fragment", "widget", "screen", "page", "viewholder", "composable") ||
            "/ui/" in pathLower || "/presentation/" in pathLower
        ) {
            add(FileKind.UI, 55, "ui signal/supertype/path hint")
        }

        if (
            AndroidPsiSignal.ANDROID_VIEWMODEL in input.androidSignals ||
            DartSignal.FLUTTER_PROVIDER in input.dartSignals ||
            DartSignal.FLUTTER_BLOC in input.dartSignals ||
            DartSignal.FLUTTER_RIVERPOD in input.dartSignals ||
            hasAny(tokens, "viewmodel", "state", "changenotifier", "bloc", "cubit", "stateNotifier".lowercase(Locale.ROOT), "provider") ||
            "/state/" in pathLower
        ) {
            add(FileKind.STATE, 55, "state-management signal")
        }

        if ("repository" in tokens || "/repository/" in pathLower) {
            add(FileKind.REPOSITORY, 60, "repository naming/path")
        }

        if (
            "/data/" in pathLower ||
            "/datasource/" in pathLower ||
            hasAny(tokens, "datasource", "remote", "local", "api", "client", "dao") ||
            AndroidPsiSignal.ANDROID_RETROFIT in input.androidSignals ||
            AndroidPsiSignal.ANDROID_ROOM in input.androidSignals
        ) {
            add(FileKind.DATA_SOURCE, 50, "data-source/room/retrofit hint")
        }

        if (
            "/service/" in pathLower ||
            "service" in fileName ||
            "usecase" in tokens ||
            "interactor" in tokens ||
            hasAny(tokens, "worker", "jobservice", "syncservice")
        ) {
            add(FileKind.SERVICE, 45, "service/usecase hint")
        }

        if (
            "/model/" in pathLower ||
            hasAny(tokens, "model", "entity", "dto", "vo", "schema") ||
            DartSignal.FLUTTER_JSON_SERIALIZABLE in input.dartSignals ||
            DartSignal.FLUTTER_FREEZED in input.dartSignals
        ) {
            add(FileKind.MODEL, 45, "model/entity/json/freezed hint")
        }

        if (scores.isEmpty()) {
            return FileKindClassification(
                path = normalize(input.path),
                kind = FileKind.OTHER,
                score = 0,
                reasons = listOf("no strong hints"),
                purposeLine = purposeLineGenerator.generate(FileKind.OTHER, input.path),
            )
        }

        val best =
            scores.entries.maxWithOrNull(
                compareBy<Map.Entry<FileKind, Int>> { it.value }.thenBy { KIND_PRIORITY.indexOf(it.key) * -1 },
            )!!
        return FileKindClassification(
            path = normalize(input.path),
            kind = best.key,
            score = best.value,
            reasons = reasons[best.key]?.distinct().orEmpty(),
            purposeLine = purposeLineGenerator.generate(best.key, input.path),
        )
    }

    fun classifyAll(inputs: List<FileKindInput>): FileKindClassificationSummary {
        val classifications = inputs.map { classify(it) }.sortedBy { it.path }
        val counts = classifications.groupingBy { it.kind }.eachCount().toSortedMap(compareBy { KIND_PRIORITY.indexOf(it) })
        val samples =
            counts.keys.associateWith { kind ->
                classifications
                    .filter { it.kind == kind }
                    .take(3)
                    .map { Path.of(it.path).fileName.toString() }
            }
        return FileKindClassificationSummary(
            classifications = classifications,
            counts = counts,
            samples = samples,
        )
    }

    private fun buildTokenBlob(
        input: FileKindInput,
        pathLower: String,
        fileName: String,
    ): String {
        return buildString {
            append(pathLower)
            append(' ')
            append(fileName)
            append(' ')
            append(input.signalTags.joinToString(" ").lowercase(Locale.ROOT))
            append(' ')
            append(input.supertypes.joinToString(" ").lowercase(Locale.ROOT))
            append(' ')
            append(input.imports.joinToString(" ").lowercase(Locale.ROOT))
            append(' ')
            append(input.declarationNames.joinToString(" ").lowercase(Locale.ROOT))
        }
    }

    private fun isTestFile(
        pathLower: String,
        fileName: String,
        tokens: String,
    ): Boolean {
        return "/test/" in pathLower ||
            "/tests/" in pathLower ||
            "/androidtest/" in pathLower ||
            fileName.endsWith("test.kt") ||
            fileName.endsWith("test.java") ||
            fileName.endsWith("_test.dart") ||
            fileName.endsWith("test.dart") ||
            "junit" in tokens ||
            "mockito" in tokens
    }

    private fun hasAny(
        text: String,
        vararg tokens: String,
    ): Boolean = tokens.any { it.lowercase(Locale.ROOT) in text }

    private fun normalize(path: String): String = Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/')

    companion object {
        private val KIND_PRIORITY =
            listOf(
                FileKind.TEST,
                FileKind.DI,
                FileKind.CONFIG,
                FileKind.UI,
                FileKind.STATE,
                FileKind.REPOSITORY,
                FileKind.DATA_SOURCE,
                FileKind.SERVICE,
                FileKind.MODEL,
                FileKind.OTHER,
            )
    }
}
