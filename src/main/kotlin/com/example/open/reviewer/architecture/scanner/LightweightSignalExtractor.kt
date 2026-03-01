package com.example.open.reviewer.architecture.scanner

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

enum class SignalExtractionMode {
    FAST,
}

data class AndroidFastSignals(
    val viewModelPresenceCount: Int,
    val hiltAnnotationCount: Int,
    val roomAnnotationCount: Int,
    val retrofitAnnotationCount: Int,
    val composeUsageCount: Int,
)

data class FlutterFastSignals(
    val providerRiverpodBlocDependencyCount: Int,
    val stateClassCount: Int,
    val runAppRootCount: Int,
    val getItUsageCount: Int,
    val freezedJsonSerializableCount: Int,
)

data class FastSignalExtractionResult(
    val extractionMode: SignalExtractionMode,
    val confidence: Double,
    val android: AndroidFastSignals,
    val flutter: FlutterFastSignals,
    val scannedFiles: Int,
    val unreadableFiles: Int,
)

class LightweightSignalExtractor {
    fun extract(
        root: Path,
        highSignalFiles: HighSignalScanResult,
    ): FastSignalExtractionResult {
        val candidatePaths =
            highSignalFiles.highSignalFiles()
                .asSequence()
                .map { Path.of(it) }
                .filter { it.startsWith(root) }
                .filter { Files.isRegularFile(it) }
                .distinct()
                .sortedBy { it.toString().replace('\\', '/') }
                .toList()

        var viewModelPresenceCount = 0
        var hiltAnnotationCount = 0
        var roomAnnotationCount = 0
        var retrofitAnnotationCount = 0
        var composeUsageCount = 0

        var providerRiverpodBlocDependencyCount = 0
        var stateClassCount = 0
        var runAppRootCount = 0
        var getItUsageCount = 0
        var freezedJsonSerializableCount = 0

        var unreadableFiles = 0

        candidatePaths.forEach { file ->
            val content = readTextSafe(file)
            if (content == null) {
                unreadableFiles += 1
                return@forEach
            }

            val fileNameLower = file.fileName.toString().lowercase()

            if (
                fileNameLower.endsWith("viewmodel.kt") ||
                fileNameLower.endsWith("viewmodel.java") ||
                VIEW_MODEL_REGEX.containsMatchIn(content)
            ) {
                viewModelPresenceCount += 1
            }

            hiltAnnotationCount += HILT_REGEX.findAll(content).count()
            roomAnnotationCount += ROOM_REGEX.findAll(content).count()
            retrofitAnnotationCount += RETROFIT_REGEX.findAll(content).count()
            composeUsageCount += COMPOSE_REGEX.findAll(content).count()

            providerRiverpodBlocDependencyCount += PROVIDER_RIVERPOD_BLOC_DEP_REGEX.findAll(content).count()
            stateClassCount += STATE_CLASS_REGEX.findAll(content).count()
            runAppRootCount += RUN_APP_REGEX.findAll(content).count()
            getItUsageCount += GET_IT_REGEX.findAll(content).count()
            freezedJsonSerializableCount += FREEZED_JSON_REGEX.findAll(content).count()
        }

        return FastSignalExtractionResult(
            extractionMode = SignalExtractionMode.FAST,
            confidence = FAST_CONFIDENCE,
            android =
                AndroidFastSignals(
                    viewModelPresenceCount = viewModelPresenceCount,
                    hiltAnnotationCount = hiltAnnotationCount,
                    roomAnnotationCount = roomAnnotationCount,
                    retrofitAnnotationCount = retrofitAnnotationCount,
                    composeUsageCount = composeUsageCount,
                ),
            flutter =
                FlutterFastSignals(
                    providerRiverpodBlocDependencyCount = providerRiverpodBlocDependencyCount,
                    stateClassCount = stateClassCount,
                    runAppRootCount = runAppRootCount,
                    getItUsageCount = getItUsageCount,
                    freezedJsonSerializableCount = freezedJsonSerializableCount,
                ),
            scannedFiles = candidatePaths.size,
            unreadableFiles = unreadableFiles,
        )
    }

    private fun readTextSafe(path: Path): String? {
        return try {
            val bytes = Files.readAllBytes(path)
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        private const val FAST_CONFIDENCE = 0.6

        private val VIEW_MODEL_REGEX = Regex("\\bViewModel\\b")
        private val HILT_REGEX = Regex("@(?:HiltAndroidApp|AndroidEntryPoint|InstallIn|Module|Provides|Binds|Inject)\\b")
        private val ROOM_REGEX = Regex("@(?:Entity|Dao|Database|Query|Insert|Delete|Update|Transaction|TypeConverter)\\b")
        private val RETROFIT_REGEX = Regex("@(?:GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|Multipart|Streaming|FormUrlEncoded)\\b|\\bRetrofit\\b")
        private val COMPOSE_REGEX = Regex("@Composable\\b|\\bsetContent\\s*\\(|\\bMaterialTheme\\b|\\bremember\\s*\\(")

        private val PROVIDER_RIVERPOD_BLOC_DEP_REGEX =
            Regex("\\b(provider|flutter_riverpod|riverpod|flutter_bloc|bloc)\\b")
        private val STATE_CLASS_REGEX =
            Regex("\\bclass\\s+[A-Za-z_][A-Za-z0-9_]*\\s*:\\s*(?:ChangeNotifier|Bloc<|Cubit<)")
        private val RUN_APP_REGEX = Regex("\\brunApp\\s*\\(")
        private val GET_IT_REGEX = Regex("\\bGetIt\\b|\\bGetIt\\.instance\\b|\\bgetIt\\b")
        private val FREEZED_JSON_REGEX = Regex("@freezed\\b|@JsonSerializable\\b|\\bfreezed\\b|\\bjson_serializable\\b")
    }
}
