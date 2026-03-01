package com.example.open.reviewer.architecture.analysis

import com.example.open.reviewer.architecture.cache.ArchitectureFileCacheService
import com.example.open.reviewer.architecture.scanner.FullProjectFileScanner
import com.example.open.reviewer.architecture.scanner.HighSignalFileLocator
import com.example.open.reviewer.architecture.scanner.LightweightSignalExtractor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ArchitectureBackgroundAnalysisPipelineTest {
    @Test
    fun `runs staged pipeline with chunk processing and skips unchanged files on second run`() {
        withTempRepo { root ->
            repeat(240) { index ->
                write(root, "lib/features/f$index/main_$index.dart", "class F$index {}")
            }
            write(root, "app/src/main/AndroidManifest.xml", "<manifest/>")
            write(root, "build.gradle.kts", "plugins {}")

            val locator = HighSignalFileLocator()
            val extractor = LightweightSignalExtractor()
            val highSignal = locator.locate(root)
            val fast = extractor.extract(root, highSignal)
            val preliminary = PreliminaryArchitectureScorer().score(root, highSignal, fast)

            val cache = ArchitectureFileCacheService()
            val pipeline = ArchitectureBackgroundAnalysisPipeline(FullProjectFileScanner(), cache, chunkSize = 25)

            val firstStages = mutableListOf<ArchitecturePipelineStage>()
            val first =
                pipeline.run(
                    root = root,
                    highSignal = highSignal,
                    fastSignals = fast,
                    preliminaryGuess = preliminary,
                    progress =
                        object : ArchitecturePipelineProgress {
                            override fun checkCanceled() = Unit

                            override fun update(
                                stage: ArchitecturePipelineStage,
                                message: String,
                                fraction: Double,
                            ) {
                                firstStages += stage
                            }
                        },
                )

            val second =
                pipeline.run(
                    root = root,
                    highSignal = highSignal,
                    fastSignals = fast,
                    preliminaryGuess = preliminary,
                    progress =
                        object : ArchitecturePipelineProgress {
                            override fun checkCanceled() = Unit

                            override fun update(
                                stage: ArchitecturePipelineStage,
                                message: String,
                                fraction: Double,
                            ) = Unit
                        },
                )

            assertTrue(first.cacheResult.analyzedCount > 0)
            assertTrue(second.cacheResult.skippedCount > 0)
            assertTrue(firstStages.contains(ArchitecturePipelineStage.SCAN))
            assertTrue(firstStages.contains(ArchitecturePipelineStage.EXTRACT_FACTS))
            assertTrue(firstStages.contains(ArchitecturePipelineStage.BUILD_SUMMARY))
            assertTrue(firstStages.contains(ArchitecturePipelineStage.UPDATE_GRAPH))
            assertTrue(firstStages.contains(ArchitecturePipelineStage.AGGREGATE))
            assertTrue(firstStages.contains(ArchitecturePipelineStage.AI_CALL))
        }
    }

    @Test
    fun `supports cancellation during pipeline`() {
        withTempRepo { root ->
            repeat(120) { index ->
                write(root, "lib/file_$index.dart", "class C$index {}")
            }
            write(root, "pubspec.yaml", "name: demo")
            write(root, "lib/main.dart", "void main(){}")

            val locator = HighSignalFileLocator()
            val extractor = LightweightSignalExtractor()
            val highSignal = locator.locate(root)
            val fast = extractor.extract(root, highSignal)
            val preliminary = PreliminaryArchitectureScorer().score(root, highSignal, fast)

            val pipeline = ArchitectureBackgroundAnalysisPipeline(FullProjectFileScanner(), ArchitectureFileCacheService(), chunkSize = 10)
            val updates = AtomicInteger(0)
            var cancelled = false
            try {
                pipeline.run(
                    root = root,
                    highSignal = highSignal,
                    fastSignals = fast,
                    preliminaryGuess = preliminary,
                    progress =
                        object : ArchitecturePipelineProgress {
                            override fun checkCanceled() {
                                if (updates.get() >= 4) {
                                    throw CancellationException("cancel")
                                }
                            }

                            override fun update(
                                stage: ArchitecturePipelineStage,
                                message: String,
                                fraction: Double,
                            ) {
                                updates.incrementAndGet()
                            }
                        },
                )
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
        }
    }

    private fun withTempRepo(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("architecture-background-pipeline-test")
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
