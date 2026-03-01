package com.example.open.reviewer.architecture.analysis

import com.example.open.reviewer.architecture.cache.CachedFileAnalysis
import java.nio.file.Path

data class RepoAggregatePayload(
    val tokenBudget: Int,
    val estimatedTokens: Int,
    val bounded: Boolean,
    val signalCounts: List<String>,
    val representativeFiles: List<String>,
    val folderStructureHints: List<String>,
    val trimmedEdgeGraph: List<String>,
)

class RepoAggregateBuilder(
    private val tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
) {
    fun build(
        root: Path,
        analyzedFiles: List<CachedFileAnalysis>,
        graph: ArchitectureGraphSnapshot,
    ): RepoAggregatePayload {
        val normalizedRoot = normalizePath(root)
        val sortedEntries = analyzedFiles.sortedBy { it.path }

        val signals = buildSignalCounts(sortedEntries)
        val representatives = buildRepresentativeFiles(sortedEntries, normalizedRoot)
        val folders = buildFolderHints(sortedEntries, normalizedRoot)
        val edges = graph.trimmedEdges

        val guardedSignals = trimByTokenBudget(signals, tokenBudget = sectionBudget(0.35))
        val guardedRepresentatives = trimByTokenBudget(representatives, tokenBudget = sectionBudget(0.35))
        val guardedFolders = trimByTokenBudget(folders, tokenBudget = sectionBudget(0.15))
        val guardedEdges = trimByTokenBudget(edges, tokenBudget = sectionBudget(0.15))

        val selectedSignals = ensureStrongSignalEvidence(guardedSignals, signals)
        val selectedReps = ensureRepresentativeEvidence(guardedRepresentatives, representatives)
        val selectedEdges = ensureEdgeEvidence(guardedEdges, edges)

        var payload =
            RepoAggregatePayload(
                tokenBudget = tokenBudget,
                estimatedTokens = 0,
                bounded = false,
                signalCounts = selectedSignals,
                representativeFiles = selectedReps,
                folderStructureHints = guardedFolders,
                trimmedEdgeGraph = selectedEdges,
            )
        payload = payload.copy(estimatedTokens = estimateTokens(payload))

        if (payload.estimatedTokens > tokenBudget) {
            val tightened = tightenPayload(payload)
            payload = tightened.copy(estimatedTokens = estimateTokens(tightened))
        }
        return payload.copy(bounded = payload.estimatedTokens <= tokenBudget)
    }

    private fun buildSignalCounts(entries: List<CachedFileAnalysis>): List<String> {
        return entries
            .flatMap { it.fileFacts.signalTags }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(MAX_SIGNALS)
            .map { "${it.key}:${it.value}" }
    }

    private fun buildRepresentativeFiles(
        entries: List<CachedFileAnalysis>,
        root: String,
    ): List<String> {
        return entries
            .sortedByDescending { representativeScore(it) }
            .take(MAX_REPRESENTATIVE_FILES)
            .map { entry ->
                val rel = toRelative(entry.path, root)
                val tags = entry.fileFacts.signalTags.distinct().take(3).joinToString("|").ifBlank { "none" }
                "$rel [L${entry.fileFacts.lineCount} C${entry.fileFacts.classCount} F${entry.fileFacts.functionCount}] {$tags}"
            }
    }

    private fun representativeScore(entry: CachedFileAnalysis): Int {
        val signalWeight = entry.fileFacts.signalTags.distinct().size * 10
        val structureWeight = entry.fileFacts.classCount * 3 + entry.fileFacts.functionCount
        val sizeWeight = (entry.fileFacts.lineCount / 40).coerceAtMost(12)
        return signalWeight + structureWeight + sizeWeight
    }

    private fun buildFolderHints(
        entries: List<CachedFileAnalysis>,
        root: String,
    ): List<String> {
        val counts = linkedMapOf<String, Int>()
        entries.forEach { entry ->
            val rel = toRelative(entry.path, root)
            val segments = rel.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return@forEach
            val hint =
                when {
                    segments.size >= 3 -> "${segments[0]}/${segments[1]}/${segments[2]}"
                    segments.size >= 2 -> "${segments[0]}/${segments[1]}"
                    else -> segments[0]
                }
            counts[hint] = (counts[hint] ?: 0) + 1
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(MAX_FOLDER_HINTS)
            .map { "${it.key}:${it.value}" }
    }

    private fun ensureStrongSignalEvidence(
        selected: List<String>,
        source: List<String>,
    ): List<String> {
        if (selected.isNotEmpty()) return selected
        return source.take(MIN_SIGNAL_EVIDENCE)
    }

    private fun ensureRepresentativeEvidence(
        selected: List<String>,
        source: List<String>,
    ): List<String> {
        if (selected.isNotEmpty()) return selected
        return source.take(MIN_REPRESENTATIVE_EVIDENCE)
    }

    private fun ensureEdgeEvidence(
        selected: List<String>,
        source: List<String>,
    ): List<String> {
        if (selected.isNotEmpty()) return selected
        return source.take(MIN_EDGE_EVIDENCE)
    }

    private fun tightenPayload(payload: RepoAggregatePayload): RepoAggregatePayload {
        var current = payload
        while (estimateTokens(current) > tokenBudget) {
            current =
                when {
                    current.folderStructureHints.size > 1 -> current.copy(folderStructureHints = current.folderStructureHints.dropLast(1))
                    current.trimmedEdgeGraph.size > MIN_EDGE_EVIDENCE -> current.copy(trimmedEdgeGraph = current.trimmedEdgeGraph.dropLast(1))
                    current.representativeFiles.size > MIN_REPRESENTATIVE_EVIDENCE -> {
                        current.copy(representativeFiles = current.representativeFiles.dropLast(1))
                    }
                    current.signalCounts.size > MIN_SIGNAL_EVIDENCE -> current.copy(signalCounts = current.signalCounts.dropLast(1))
                    else -> break
                }
        }
        return current
    }

    private fun trimByTokenBudget(
        values: List<String>,
        tokenBudget: Int,
    ): List<String> {
        if (tokenBudget <= 0 || values.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var used = 0
        values.forEach { value ->
            val tokens = estimateTokens(value)
            if (used + tokens > tokenBudget) return@forEach
            out += value
            used += tokens
        }
        return out
    }

    private fun sectionBudget(ratio: Double): Int = (tokenBudget * ratio).toInt().coerceAtLeast(1)

    private fun estimateTokens(payload: RepoAggregatePayload): Int {
        val text =
            buildString {
                append(payload.signalCounts.joinToString("\n"))
                append('\n')
                append(payload.representativeFiles.joinToString("\n"))
                append('\n')
                append(payload.folderStructureHints.joinToString("\n"))
                append('\n')
                append(payload.trimmedEdgeGraph.joinToString("\n"))
            }
        return estimateTokens(text)
    }

    private fun estimateTokens(text: String): Int = ((text.length / 4.0) + 1.0).toInt()

    private fun toRelative(
        absolutePath: String,
        root: String,
    ): String {
        val normalizedPath = absolutePath.replace('\\', '/')
        val normalizedRoot = if (root.endsWith("/")) root else "$root/"
        return normalizedPath.removePrefix(normalizedRoot)
    }

    private fun normalizePath(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/')

    companion object {
        private const val DEFAULT_TOKEN_BUDGET = 900
        private const val MAX_SIGNALS = 12
        private const val MAX_REPRESENTATIVE_FILES = 20
        private const val MAX_FOLDER_HINTS = 15
        private const val MIN_SIGNAL_EVIDENCE = 1
        private const val MIN_REPRESENTATIVE_EVIDENCE = 1
        private const val MIN_EDGE_EVIDENCE = 1
    }
}
