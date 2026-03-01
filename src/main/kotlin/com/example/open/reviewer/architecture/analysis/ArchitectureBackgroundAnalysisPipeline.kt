package com.example.open.reviewer.architecture.analysis

import com.example.open.reviewer.architecture.analysis.extractors.AndroidPsiSignal
import com.example.open.reviewer.architecture.analysis.extractors.DartExtractionMode
import com.example.open.reviewer.architecture.analysis.extractors.DartPsiExtractor
import com.example.open.reviewer.architecture.analysis.extractors.DartSignal
import com.example.open.reviewer.architecture.analysis.extractors.JavaPsiExtractor
import com.example.open.reviewer.architecture.analysis.extractors.KotlinPsiExtractor
import com.example.open.reviewer.architecture.classifier.FileKindClassificationSummary
import com.example.open.reviewer.architecture.classifier.FileKindClassifier
import com.example.open.reviewer.architecture.classifier.FileKindInput
import com.example.open.reviewer.architecture.cache.ArchitectureFileCacheService
import com.example.open.reviewer.architecture.cache.CacheAnalysisRunResult
import com.example.open.reviewer.architecture.cache.CachedFileAnalysis
import com.example.open.reviewer.architecture.model.PreliminaryArchitectureGuess
import com.example.open.reviewer.architecture.scanner.FastSignalExtractionResult
import com.example.open.reviewer.architecture.scanner.FullProjectFileScanner
import com.example.open.reviewer.architecture.scanner.FullProjectScanProgress
import com.example.open.reviewer.architecture.scanner.FullProjectScanResult
import com.example.open.reviewer.architecture.scanner.HighSignalScanResult
import com.intellij.openapi.project.Project
import java.nio.file.Path

enum class ArchitecturePipelineStage {
    SCAN,
    EXTRACT_FACTS,
    BUILD_SUMMARY,
    UPDATE_GRAPH,
    AGGREGATE,
    AI_CALL,
}

data class ArchitectureGraphSnapshot(
    val nodeCount: Int,
    val edgeCount: Int,
    val topNodes: List<String>,
    val entrypoints: List<String>,
    val trimmedEdges: List<String>,
)

data class ArchitectureAggregateSummary(
    val fileCount: Int,
    val totalLines: Int,
    val totalClasses: Int,
    val totalFunctions: Int,
    val topSignalTags: List<String>,
)

data class ArchitectureAiStageResult(
    val used: Boolean,
    val summary: String,
    val parsed: Boolean = false,
    val normalizedPatterns: List<ArchitectureDetectedPattern> = emptyList(),
    val graphHints: ArchitectureGraphHints = ArchitectureGraphHints(),
    val rawResponse: String? = null,
)

data class KotlinPsiStageSummary(
    val scannedFiles: Int,
    val skippedLargeFiles: Int,
    val unresolvedFiles: Int,
    val signals: Set<AndroidPsiSignal>,
)

data class JavaPsiStageSummary(
    val scannedFiles: Int,
    val skippedLargeFiles: Int,
    val unresolvedFiles: Int,
    val signals: Set<AndroidPsiSignal>,
)

data class DartPsiStageSummary(
    val scannedFiles: Int,
    val unresolvedFiles: Int,
    val psiFiles: Int,
    val fallbackFiles: Int,
    val extractionMode: DartExtractionMode,
    val confidence: Double,
    val signals: Set<DartSignal>,
    val hasMainEntrypoint: Boolean,
)

data class ArchitectureBackgroundPipelineResult(
    val deepScan: FullProjectScanResult,
    val cacheResult: CacheAnalysisRunResult,
    val analyzedFiles: List<CachedFileAnalysis>,
    val gradle: GradleAnalysisResult,
    val manifest: AndroidManifestAnalysisResult,
    val kotlinPsi: KotlinPsiStageSummary,
    val javaPsi: JavaPsiStageSummary,
    val dartPsi: DartPsiStageSummary,
    val fileKinds: FileKindClassificationSummary,
    val graph: ArchitectureGraphSnapshot,
    val aggregate: ArchitectureAggregateSummary,
    val repoAggregate: RepoAggregatePayload,
    val ai: ArchitectureAiStageResult,
)

interface ArchitecturePipelineProgress {
    fun checkCanceled()

    fun update(
        stage: ArchitecturePipelineStage,
        message: String,
        fraction: Double,
    )
}

class ArchitectureBackgroundAnalysisPipeline(
    private val deepScanner: FullProjectFileScanner,
    private val fileCache: ArchitectureFileCacheService,
    private val gradleAnalyzer: GradleAnalyzer = GradleAnalyzer(),
    private val manifestAnalyzer: AndroidManifestAnalyzer = AndroidManifestAnalyzer(),
    private val importGraphBuilder: ImportDependencyGraphBuilder = ImportDependencyGraphBuilder(),
    private val repoAggregateBuilder: RepoAggregateBuilder = RepoAggregateBuilder(),
    private val architectureAiClient: ArchitectureAiAnalysisClient = ArchitectureAiAnalysisClient(),
    private val kotlinPsiExtractor: KotlinPsiExtractor = KotlinPsiExtractor(),
    private val javaPsiExtractor: JavaPsiExtractor = JavaPsiExtractor(),
    private val dartPsiExtractor: DartPsiExtractor = DartPsiExtractor(),
    private val fileKindClassifier: FileKindClassifier = FileKindClassifier(),
    private val chunkSize: Int = 120,
) {
    constructor(
        deepScanner: FullProjectFileScanner,
        fileCache: ArchitectureFileCacheService,
        chunkSize: Int,
    ) : this(
        deepScanner = deepScanner,
        fileCache = fileCache,
        gradleAnalyzer = GradleAnalyzer(),
        manifestAnalyzer = AndroidManifestAnalyzer(),
        importGraphBuilder = ImportDependencyGraphBuilder(),
        repoAggregateBuilder = RepoAggregateBuilder(),
        architectureAiClient = ArchitectureAiAnalysisClient(),
        kotlinPsiExtractor = KotlinPsiExtractor(),
        javaPsiExtractor = JavaPsiExtractor(),
        dartPsiExtractor = DartPsiExtractor(),
        fileKindClassifier = FileKindClassifier(),
        chunkSize = chunkSize,
    )

    fun run(
        root: Path,
        highSignal: HighSignalScanResult,
        fastSignals: FastSignalExtractionResult,
        preliminaryGuess: PreliminaryArchitectureGuess,
        progress: ArchitecturePipelineProgress,
    ): ArchitectureBackgroundPipelineResult {
        return runInternal(
            project = null,
            root = root,
            highSignal = highSignal,
            fastSignals = fastSignals,
            preliminaryGuess = preliminaryGuess,
            progress = progress,
        )
    }

    fun run(
        project: Project,
        root: Path,
        highSignal: HighSignalScanResult,
        fastSignals: FastSignalExtractionResult,
        preliminaryGuess: PreliminaryArchitectureGuess,
        progress: ArchitecturePipelineProgress,
    ): ArchitectureBackgroundPipelineResult {
        return runInternal(project, root, highSignal, fastSignals, preliminaryGuess, progress)
    }

    private fun runInternal(
        project: Project?,
        root: Path,
        highSignal: HighSignalScanResult,
        fastSignals: FastSignalExtractionResult,
        preliminaryGuess: PreliminaryArchitectureGuess,
        progress: ArchitecturePipelineProgress,
    ): ArchitectureBackgroundPipelineResult {
        progress.update(ArchitecturePipelineStage.SCAN, "Scanning project files", 0.05)
        val deepScan =
            deepScanner.scan(
                root = root,
                progress =
                    object : FullProjectScanProgress {
                        override fun checkCanceled() {
                            progress.checkCanceled()
                        }

                        override fun onProgress(
                            scannedFiles: Int,
                            collectedFiles: Int,
                            currentPath: String,
                        ) {
                            val fraction = (scannedFiles / 20_000.0).coerceIn(0.05, 0.35)
                            progress.update(
                                ArchitecturePipelineStage.SCAN,
                                "Scanned $scannedFiles files • matched $collectedFiles",
                                fraction,
                            )
                        }
                    },
            )

        val allFiles = deepScan.allFiles()
        val chunks = allFiles.chunked(chunkSize.coerceAtLeast(1))
        var analyzed = 0
        var skipped = 0
        var unreadable = 0
        val analyzedEntries = mutableListOf<CachedFileAnalysis>()

        chunks.forEachIndexed { index, chunk ->
            progress.checkCanceled()
            val chunkResult = fileCache.analyzeFiles(chunk)
            analyzed += chunkResult.analyzedCount
            skipped += chunkResult.skippedCount
            unreadable += chunkResult.unreadableCount
            analyzedEntries += chunkResult.analyzedEntries
            val fraction = 0.35 + ((index + 1) / chunks.size.toDouble()) * 0.25
            progress.update(
                ArchitecturePipelineStage.EXTRACT_FACTS,
                "Processed chunk ${index + 1}/${chunks.size} • analyzed=$analyzed skipped=$skipped",
                fraction.coerceIn(0.35, 0.60),
            )
        }

        progress.checkCanceled()
        progress.update(ArchitecturePipelineStage.BUILD_SUMMARY, "Building summaries + Kotlin PSI extraction", 0.70)
        val analyzedFiles = fileCache.getEntries(allFiles)
        val gradleResult = gradleAnalyzer.analyze(deepScan.gradleFiles)
        val manifestResult = manifestAnalyzer.analyze(deepScan.manifestFiles)
        val kotlinPsiResult = if (project == null) null else kotlinPsiExtractor.extract(project = project, kotlinPaths = deepScan.kotlinFiles)
        val javaPsiResult = if (project == null) null else javaPsiExtractor.extract(project = project, javaPaths = deepScan.javaFiles)
        val dartResult = if (project == null) null else dartPsiExtractor.extract(project = project, dartPaths = deepScan.dartFiles)

        val kotlinPsiSummary =
            if (kotlinPsiResult == null) {
                KotlinPsiStageSummary(
                    scannedFiles = 0,
                    skippedLargeFiles = 0,
                    unresolvedFiles = 0,
                    signals = emptySet(),
                )
            } else {
                KotlinPsiStageSummary(
                    scannedFiles = kotlinPsiResult.scannedFiles,
                    skippedLargeFiles = kotlinPsiResult.skippedLargeFiles,
                    unresolvedFiles = kotlinPsiResult.unresolvedFiles,
                    signals = kotlinPsiResult.allSignals(),
                )
            }
        val javaPsiSummary =
            if (javaPsiResult == null) {
                JavaPsiStageSummary(
                    scannedFiles = 0,
                    skippedLargeFiles = 0,
                    unresolvedFiles = 0,
                    signals = emptySet(),
                )
            } else {
                JavaPsiStageSummary(
                    scannedFiles = javaPsiResult.scannedFiles,
                    skippedLargeFiles = javaPsiResult.skippedLargeFiles,
                    unresolvedFiles = javaPsiResult.unresolvedFiles,
                    signals = javaPsiResult.allSignals(),
                )
            }
        val kotlinByPath = kotlinPsiResult?.files?.associateBy { it.path }.orEmpty()
        val javaByPath = javaPsiResult?.files?.associateBy { it.path }.orEmpty()
        val dartByPath = dartResult?.files?.associateBy { it.path }.orEmpty()
        val dartPsiSummary =
            if (dartResult == null) {
                DartPsiStageSummary(
                    scannedFiles = 0,
                    unresolvedFiles = 0,
                    psiFiles = 0,
                    fallbackFiles = 0,
                    extractionMode = DartExtractionMode.FALLBACK,
                    confidence = 0.5,
                    signals = emptySet(),
                    hasMainEntrypoint = false,
                )
            } else {
                val mode = if (dartResult.psiFiles > 0) DartExtractionMode.PSI else DartExtractionMode.FALLBACK
                val confidence = if (mode == DartExtractionMode.PSI) 0.8 else 0.5
                DartPsiStageSummary(
                    scannedFiles = dartResult.scannedFiles,
                    unresolvedFiles = dartResult.unresolvedFiles,
                    psiFiles = dartResult.psiFiles,
                    fallbackFiles = dartResult.fallbackFiles,
                    extractionMode = mode,
                    confidence = confidence,
                    signals = dartResult.allSignals(),
                    hasMainEntrypoint = dartResult.files.any { it.hasMain },
                )
            }
        val fileKindSummary =
            fileKindClassifier.classifyAll(
                allFiles.map { path ->
                    val cached = analyzedFiles.firstOrNull { it.path == path }
                    val kotlin = kotlinByPath[path]
                    val java = javaByPath[path]
                    val dart = dartByPath[path]
                    FileKindInput(
                        path = path,
                        signalTags = cached?.fileFacts?.signalTags?.toSet().orEmpty(),
                        supertypes =
                            buildSet {
                                kotlin?.declarations?.flatMapTo(this) { it.supertypes }
                                java?.declarations?.flatMapTo(this) { it.supertypes }
                            },
                        imports =
                            buildSet {
                                kotlin?.imports?.forEach { add(it) }
                                java?.imports?.forEach { add(it) }
                                dart?.imports?.forEach { add(it) }
                            },
                        declarationNames =
                            buildSet {
                                kotlin?.declarations?.forEach { add(it.name) }
                                java?.declarations?.forEach { add(it.name) }
                                dart?.widgets?.forEach { add(it) }
                                dart?.changeNotifiers?.forEach { add(it) }
                                dart?.blocCubits?.forEach { add(it) }
                                dart?.riverpodTypes?.forEach { add(it) }
                            },
                        androidSignals =
                            buildSet {
                                kotlin?.signals?.forEach { add(it) }
                                java?.signals?.forEach { add(it) }
                            },
                        dartSignals = dart?.signals.orEmpty(),
                    )
                },
            )

        progress.checkCanceled()
        progress.update(ArchitecturePipelineStage.UPDATE_GRAPH, "Updating architecture graph", 0.80)
        val graph =
            buildGraph(
                root = root,
                allFiles = allFiles,
                manifestEntrypoints = manifestResult.entrypoints(),
            )

        progress.checkCanceled()
        progress.update(ArchitecturePipelineStage.AGGREGATE, "Aggregating architecture metrics", 0.90)
        val aggregate = aggregate(analyzedFiles)
        val repoAggregate = repoAggregateBuilder.build(root = root, analyzedFiles = analyzedFiles, graph = graph)

        progress.checkCanceled()
        progress.update(ArchitecturePipelineStage.AI_CALL, "Running AI call", 0.96)
        val ai =
            architectureAiClient.analyze(
                request =
                    ArchitectureAiRequest(
                        repoAggregate = repoAggregate,
                        preliminaryTopPattern = preliminaryGuess.topPattern?.name,
                        preliminaryTopConfidence = preliminaryGuess.topConfidence,
                        fileDigests = buildAiFileDigests(root, analyzedFiles, fileKindSummary),
                    ),
                checkCanceled = { progress.checkCanceled() },
            )

        progress.update(ArchitecturePipelineStage.AI_CALL, "Pipeline complete", 1.0)
        return ArchitectureBackgroundPipelineResult(
            deepScan = deepScan,
            cacheResult =
                CacheAnalysisRunResult(
                    analyzedCount = analyzed,
                    skippedCount = skipped,
                    unreadableCount = unreadable,
                    analyzedEntries = analyzedEntries,
                ),
            analyzedFiles = analyzedFiles,
            gradle = gradleResult,
            manifest = manifestResult,
            kotlinPsi = kotlinPsiSummary,
            javaPsi = javaPsiSummary,
            dartPsi = dartPsiSummary,
            fileKinds = fileKindSummary,
            graph = graph,
            aggregate = aggregate,
            repoAggregate = repoAggregate,
            ai = ai,
        )
    }

    private fun buildGraph(
        root: Path,
        allFiles: List<String>,
        manifestEntrypoints: List<String>,
    ): ArchitectureGraphSnapshot {
        return importGraphBuilder.build(
            root = root,
            allFiles = allFiles,
            manifestEntrypoints = manifestEntrypoints,
        )
    }

    private fun aggregate(entries: List<CachedFileAnalysis>): ArchitectureAggregateSummary {
        val topTags =
            entries
                .flatMap { it.fileFacts.signalTags }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(5)
                .map { "${it.key}(${it.value})" }
        return ArchitectureAggregateSummary(
            fileCount = entries.size,
            totalLines = entries.sumOf { it.fileFacts.lineCount },
            totalClasses = entries.sumOf { it.fileFacts.classCount },
            totalFunctions = entries.sumOf { it.fileFacts.functionCount },
            topSignalTags = topTags,
        )
    }

    private fun buildAiFileDigests(
        root: Path,
        analyzedFiles: List<CachedFileAnalysis>,
        fileKindSummary: FileKindClassificationSummary,
    ): List<ArchitectureAiFileDigest> {
        val normalizedRoot = root.toAbsolutePath().normalize().toString().replace('\\', '/')
        val kindByRelative =
            fileKindSummary.classifications.associate { item ->
                val relative = toRelativePath(item.path, normalizedRoot)
                relative to item.kind.name
            }
        return analyzedFiles
            .sortedByDescending { it.fileFacts.signalTags.distinct().size * 10 + it.fileFacts.classCount * 3 + it.fileFacts.functionCount }
            .take(MAX_AI_DIGEST_FILES)
            .map { file ->
                val relative = toRelativePath(file.path, normalizedRoot)
                ArchitectureAiFileDigest(
                    path = relative,
                    kind = kindByRelative[relative] ?: "OTHER",
                    signals = file.fileFacts.signalTags.distinct().sorted().take(6),
                    headline = file.fileSummary.headline.take(180),
                    keyPoints = file.fileSummary.keyPoints.take(4).map { it.take(220) },
                )
            }
    }

    private fun toRelativePath(
        absolutePath: String,
        normalizedRoot: String,
    ): String {
        val normalizedPath = absolutePath.replace('\\', '/')
        val rootPrefix = if (normalizedRoot.endsWith("/")) normalizedRoot else "$normalizedRoot/"
        return normalizedPath.removePrefix(rootPrefix)
    }

    companion object {
        private const val MAX_AI_DIGEST_FILES = 70
    }

}
