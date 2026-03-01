package com.example.open.reviewer.architecture.cache

import com.example.open.reviewer.architecture.model.FileFacts
import com.example.open.reviewer.architecture.model.FileSummary
import com.example.open.reviewer.architecture.summary.FileSummaryBuilder
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class CachedFileAnalysis(
    var path: String = "",
    var hash: String = "",
    var fileFacts: FileFacts = FileFacts(),
    var fileSummary: FileSummary = FileSummary(),
    var lastAnalyzedAtMillis: Long = 0L,
)

data class ArchitectureFileCacheState(
    var entries: MutableList<CachedFileAnalysis> = mutableListOf(),
)

data class CacheAnalysisRunResult(
    val analyzedCount: Int,
    val skippedCount: Int,
    val unreadableCount: Int,
    val analyzedEntries: List<CachedFileAnalysis>,
)

@Service(Service.Level.PROJECT)
@State(name = "OpenReviewerArchitectureFileCache", storages = [Storage("open-reviewer-architecture-cache.xml")])
class ArchitectureFileCacheService : PersistentStateComponent<ArchitectureFileCacheState> {
    private var state = ArchitectureFileCacheState()
    private val byPath = linkedMapOf<String, CachedFileAnalysis>()
    private val fileSummaryBuilder = FileSummaryBuilder()

    override fun getState(): ArchitectureFileCacheState {
        state.entries = byPath.values.toMutableList()
        return state
    }

    override fun loadState(state: ArchitectureFileCacheState) {
        this.state = state
        byPath.clear()
        state.entries.forEach { entry ->
            if (entry.path.isNotBlank()) {
                byPath[entry.path] = entry
            }
        }
    }

    fun analyzeFiles(
        paths: List<String>,
        indicator: ProgressIndicator? = null,
    ): CacheAnalysisRunResult {
        var analyzedCount = 0
        var skippedCount = 0
        var unreadableCount = 0
        val analyzedEntries = mutableListOf<CachedFileAnalysis>()
        val total = paths.size.coerceAtLeast(1)

        paths.sorted().forEachIndexed { index, rawPath ->
            indicator?.checkCanceled()
            val normalized = normalizePath(Path.of(rawPath))
            val bytes = runCatching { Files.readAllBytes(Path.of(normalized)) }.getOrElse {
                unreadableCount += 1
                return@forEachIndexed
            }
            val hash = sha256(bytes)
            val existing = byPath[normalized]
            if (existing != null && existing.hash == hash) {
                skippedCount += 1
            } else {
                val content = String(bytes, StandardCharsets.UTF_8)
                val facts = extractFacts(content)
                val updated =
                    CachedFileAnalysis(
                        path = normalized,
                        hash = hash,
                        fileFacts = facts,
                        fileSummary = fileSummaryBuilder.build(normalized, facts, content),
                        lastAnalyzedAtMillis = System.currentTimeMillis(),
                    )
                byPath[normalized] = updated
                analyzedEntries += updated
                analyzedCount += 1
            }

            indicator?.text2 = "Cache analysis ${index + 1}/${total} • analyzed=$analyzedCount skipped=$skippedCount"
            indicator?.fraction = ((index + 1) / total.toDouble()).coerceIn(0.0, 1.0)
        }

        state.entries = byPath.values.toMutableList()
        return CacheAnalysisRunResult(
            analyzedCount = analyzedCount,
            skippedCount = skippedCount,
            unreadableCount = unreadableCount,
            analyzedEntries = analyzedEntries,
        )
    }

    fun clearCache() {
        byPath.clear()
        state.entries = mutableListOf()
    }

    fun getEntry(path: String): CachedFileAnalysis? = byPath[normalizePath(Path.of(path))]

    fun getEntries(paths: List<String>): List<CachedFileAnalysis> {
        return paths
            .asSequence()
            .map { normalizePath(Path.of(it)) }
            .mapNotNull { byPath[it] }
            .toList()
    }

    fun size(): Int = byPath.size

    private fun extractFacts(content: String): FileFacts {
        val lines = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
        val classCount = Regex("\\bclass\\s+[A-Za-z_][A-Za-z0-9_]*").findAll(content).count()
        val functionCount = Regex("\\bfun\\s+[A-Za-z_][A-Za-z0-9_]*|\\bvoid\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\(").findAll(content).count()
        val importCount = Regex("^\\s*import\\s+", setOf(RegexOption.MULTILINE)).findAll(content).count()
        val tags = mutableListOf<String>()
        if (Regex("\\bViewModel\\b").containsMatchIn(content)) tags += "viewmodel"
        if (Regex("@Composable\\b|\\bWidget\\b").containsMatchIn(content)) tags += "ui"
        if (Regex("\\bRepository\\b").containsMatchIn(content)) tags += "repository"
        if (Regex("\\bBloc\\b|\\bCubit\\b|riverpod|provider").containsMatchIn(content)) tags += "state-management"
        return FileFacts(
            lineCount = lines,
            classCount = classCount,
            functionCount = functionCount,
            importCount = importCount,
            signalTags = tags.distinct().toMutableList(),
        )
    }

    private fun normalizePath(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/')

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        fun getInstance(project: Project): ArchitectureFileCacheService =
            project.getService(ArchitectureFileCacheService::class.java)
    }
}
