package com.example.open.reviewer.architecture.analysis

import com.example.open.reviewer.architecture.cache.CachedFileAnalysis
import com.example.open.reviewer.architecture.model.FileFacts
import com.example.open.reviewer.architecture.model.FileSummary
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RepoAggregateBuilderTest {
    @Test
    fun `payload stays bounded by token budget`() {
        val root = Files.createTempDirectory("repo-aggregate-budget-test")
        try {
            val entries =
                (1..20).map { index ->
                    CachedFileAnalysis(
                        path = root.resolve("lib/feature$index/presentation/Screen$index.kt").toString(),
                        hash = "$index",
                        fileFacts =
                            FileFacts(
                                lineCount = 200 + index,
                                classCount = 2,
                                functionCount = 6,
                                importCount = 5,
                                signalTags = mutableListOf("ui", "viewmodel", "repository", "state-management"),
                            ),
                        fileSummary = FileSummary(headline = "Feature$index", keyPoints = mutableListOf("point")),
                        lastAnalyzedAtMillis = index.toLong(),
                    )
                }
            val graph =
                ArchitectureGraphSnapshot(
                    nodeCount = 100,
                    edgeCount = 120,
                    topNodes = listOf("a(10)"),
                    entrypoints = listOf("com.example.MainActivity"),
                    trimmedEdges = (1..40).map { "lib/feature$it/ui/Screen$it.kt -> lib/feature$it/vm/ViewModel$it.kt" },
                )

            val payload = RepoAggregateBuilder(tokenBudget = 80).build(root = root, analyzedFiles = entries, graph = graph)

            assertTrue(payload.bounded)
            assertTrue(payload.estimatedTokens <= payload.tokenBudget)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `strong evidence is preserved under trimming`() {
        val root = Files.createTempDirectory("repo-aggregate-evidence-test")
        try {
            val entries =
                listOf(
                    CachedFileAnalysis(
                        path = root.resolve("app/src/main/java/com/example/ui/MainActivity.kt").toString(),
                        hash = "1",
                        fileFacts =
                            FileFacts(
                                lineCount = 180,
                                classCount = 1,
                                functionCount = 8,
                                importCount = 12,
                                signalTags = mutableListOf("ui", "viewmodel"),
                            ),
                        fileSummary = FileSummary(headline = "Main activity", keyPoints = mutableListOf("entry")),
                        lastAnalyzedAtMillis = 1,
                    ),
                    CachedFileAnalysis(
                        path = root.resolve("app/src/main/java/com/example/vm/MainViewModel.kt").toString(),
                        hash = "2",
                        fileFacts =
                            FileFacts(
                                lineCount = 140,
                                classCount = 1,
                                functionCount = 5,
                                importCount = 8,
                                signalTags = mutableListOf("viewmodel", "repository"),
                            ),
                        fileSummary = FileSummary(headline = "VM", keyPoints = mutableListOf("state")),
                        lastAnalyzedAtMillis = 2,
                    ),
                )
            val graph =
                ArchitectureGraphSnapshot(
                    nodeCount = 10,
                    edgeCount = 20,
                    topNodes = listOf("MainActivity(5)"),
                    entrypoints = listOf("com.example.MainActivity"),
                    trimmedEdges = listOf("app/src/main/java/com/example/ui/MainActivity.kt -> app/src/main/java/com/example/vm/MainViewModel.kt"),
                )

            val payload = RepoAggregateBuilder(tokenBudget = 35).build(root = root, analyzedFiles = entries, graph = graph)

            assertTrue(payload.signalCounts.isNotEmpty())
            assertTrue(payload.representativeFiles.isNotEmpty())
            assertTrue(payload.trimmedEdgeGraph.isNotEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
