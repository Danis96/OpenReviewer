package com.example.open.reviewer.architecture.analysis

import com.example.open.reviewer.architecture.cache.CachedFileAnalysis
import com.example.open.reviewer.architecture.classifier.FileKind
import com.example.open.reviewer.architecture.classifier.FileKindClassificationSummary
import java.nio.file.Path

data class CytoscapeGraphNodeMetrics(
    val fanIn: Int,
    val fanOut: Int,
)

data class CytoscapeGraphNode(
    val id: String,
    val label: String,
    val path: String,
    val kind: String,
    val platform: String,
    val signals: List<String>,
    val group: String,
    val metrics: CytoscapeGraphNodeMetrics,
    val hotspotScore: Int = 0,
)

data class CytoscapeGraphEdge(
    val id: String,
    val source: String,
    val target: String,
    val edgeType: String,
    val weight: Int,
    val count: Int,
)

data class CytoscapeGraphPayload(
    val version: Int,
    val bounded: Boolean,
    val nodeLimit: Int,
    val edgeLimit: Int,
    val droppedNodeCount: Int,
    val droppedEdgeCount: Int,
    val nodes: List<CytoscapeGraphNode>,
    val edges: List<CytoscapeGraphEdge>,
    val summary: String,
)

data class CytoscapeGraphPayloadSettings(
    val maxNodes: Int = 700,
    val maxEdges: Int = 2_500,
    val maxJsonChars: Int = 350_000,
)

class CytoscapeGraphPayloadBuilder(
    private val settings: CytoscapeGraphPayloadSettings = CytoscapeGraphPayloadSettings(),
) {
    fun buildPayload(
        root: Path,
        graphSummary: ArchitectureGraphSnapshot,
        repoAggregate: RepoAggregatePayload,
        fileKindSummary: FileKindClassificationSummary,
        analyzedFiles: List<CachedFileAnalysis>,
        summary: String,
        aiHints: ArchitectureGraphHints = ArchitectureGraphHints(),
    ): CytoscapeGraphPayload {
        val rootNormalized = normalize(root.toAbsolutePath().normalize().toString())
        val kindByRelative = fileKindSummary.classifications.associate { toRelative(it.path, rootNormalized) to kindName(it.kind) }
        val signalByRelative = analyzedFiles.associate { toRelative(it.path, rootNormalized) to it.fileFacts.signalTags.distinct().sorted() }

        val candidateLines = repoAggregate.trimmedEdgeGraph.ifEmpty { graphSummary.trimmedEdges }
        val parsedEdges = parseEdges(candidateLines)
        val ranked =
            rankEdges(
                edges = parsedEdges,
                kindByPath = kindByRelative,
                focusPaths = aiHints.focusPaths.toSet(),
                focusEdges = aiHints.focusEdges.map { "${it.source}->${it.target}" }.toSet(),
            )
        var selected = ranked.take(settings.maxEdges).map { it.edge }
        val initialUniqueNodes = parsedEdges.flatMap { listOf(it.source, it.target) }.toSet().size
        selected = trimByNodeLimit(selected, settings.maxNodes)

        val fanIn = linkedMapOf<String, Int>()
        val fanOut = linkedMapOf<String, Int>()
        selected.forEach { edge ->
            fanOut[edge.source] = (fanOut[edge.source] ?: 0) + 1
            fanIn[edge.target] = (fanIn[edge.target] ?: 0) + 1
        }

        val nodeIds =
            buildSet {
                selected.flatMapTo(this) { listOf(it.source, it.target) }
                aiHints.focusPaths
                    .take(MAX_HINTED_FOCUS_NODES)
                    .map { it.trim().trimStart('/') }
                    .filter { it.isNotBlank() }
                    .forEach { add(it) }
            }.toList()
        val nodes =
            nodeIds.map { id ->
                val relative = id.removePrefix("entrypoint:")
                val kind = if (id.startsWith("entrypoint:")) "CONFIG" else kindByRelative[relative] ?: inferKindFromPath(relative)
                CytoscapeGraphNode(
                    id = id,
                    label = labelFor(id),
                    path = if (id.startsWith("entrypoint:")) relative else relative,
                    kind = kind,
                    platform = inferPlatform(relative),
                    signals = signalByRelative[relative].orEmpty().take(MAX_SIGNALS_PER_NODE),
                    group = groupFor(relative),
                    metrics =
                        CytoscapeGraphNodeMetrics(
                            fanIn = fanIn[id] ?: 0,
                            fanOut = fanOut[id] ?: 0,
                        ),
                    hotspotScore = (fanIn[id] ?: 0) + (fanOut[id] ?: 0),
                )
            }
        val edges =
            selected.mapIndexed { index, edge ->
                CytoscapeGraphEdge(
                    id = "e$index",
                    source = edge.source,
                    target = edge.target,
                    edgeType = edge.type,
                    weight = edge.score,
                    count = 1,
                )
            }

        var payload =
            CytoscapeGraphPayload(
                version = 1,
                bounded = false,
                nodeLimit = settings.maxNodes,
                edgeLimit = settings.maxEdges,
                droppedNodeCount = (initialUniqueNodes - nodes.size).coerceAtLeast(0),
                droppedEdgeCount = (parsedEdges.size - edges.size).coerceAtLeast(0),
                nodes = nodes,
                edges = edges,
                summary = summary,
            )

        var json = payload.toJsonString()
        while (json.length > settings.maxJsonChars && payload.edges.size > 1) {
            val reducedEdges = payload.edges.dropLast((payload.edges.size / 8).coerceAtLeast(1))
            val keepNodeIds = reducedEdges.flatMap { listOf(it.source, it.target) }.toSet()
            val reducedNodes = payload.nodes.filter { it.id in keepNodeIds }
            payload =
                payload.copy(
                    nodes = reducedNodes,
                    edges = reducedEdges,
                    droppedNodeCount = (initialUniqueNodes - reducedNodes.size).coerceAtLeast(0),
                    droppedEdgeCount = (parsedEdges.size - reducedEdges.size).coerceAtLeast(0),
                )
            json = payload.toJsonString()
        }

        if (json.length <= settings.maxJsonChars) {
            payload = payload.copy(bounded = true)
        }
        return payload
    }

    fun buildJson(
        root: Path,
        graphSummary: ArchitectureGraphSnapshot,
        repoAggregate: RepoAggregatePayload,
        fileKindSummary: FileKindClassificationSummary,
        analyzedFiles: List<CachedFileAnalysis>,
        summary: String,
        aiHints: ArchitectureGraphHints = ArchitectureGraphHints(),
    ): String {
        return toJson(
            buildPayload(
                root = root,
                graphSummary = graphSummary,
                repoAggregate = repoAggregate,
                fileKindSummary = fileKindSummary,
                analyzedFiles = analyzedFiles,
                summary = summary,
                aiHints = aiHints,
            ),
        )
    }

    fun toJson(payload: CytoscapeGraphPayload): String = payload.toJsonString()

    fun limits(): CytoscapeGraphPayloadSettings = settings
    

    private fun parseEdges(lines: List<String>): List<ScoredEdge> {
        return lines
            .asSequence()
            .map { it.trim().removePrefix("- ").trim() }
            .mapNotNull { line ->
                val parts = line.split("->").map { it.trim() }
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    ScoredEdge(source = parts[0], target = parts[1], type = "IMPORT", score = 0)
                } else {
                    null
                }
            }.distinctBy { "${it.source}->${it.target}" }
            .toList()
    }

    private fun rankEdges(
        edges: List<ScoredEdge>,
        kindByPath: Map<String, String>,
        focusPaths: Set<String>,
        focusEdges: Set<String>,
    ): List<RankedEdge> {
        return edges
            .map { edge ->
                val sourceKind = kindByPath[edge.source].orEmpty()
                val targetKind = kindByPath[edge.target].orEmpty()
                val edgeType = inferEdgeType(edge.source, edge.target, sourceKind, targetKind)
                val score =
                    edgePriority(
                        source = edge.source,
                        target = edge.target,
                        sourceKind = sourceKind,
                        targetKind = targetKind,
                        edgeType = edgeType,
                        focusPaths = focusPaths,
                        focusEdges = focusEdges,
                    )
                RankedEdge(edge = edge.copy(type = edgeType, score = score), score = score)
            }.sortedByDescending { it.score }
    }

    private fun trimByNodeLimit(
        edges: List<ScoredEdge>,
        nodeLimit: Int,
    ): List<ScoredEdge> {
        if (nodeLimit <= 0) return emptyList()
        val kept = mutableListOf<ScoredEdge>()
        val seenNodes = linkedSetOf<String>()
        edges.forEach { edge ->
            val after = seenNodes + edge.source + edge.target
            if (after.size > nodeLimit) return@forEach
            kept += edge
            seenNodes += edge.source
            seenNodes += edge.target
        }
        return kept.ifEmpty { edges.take(1) }
    }

    private fun inferEdgeType(
        source: String,
        target: String,
        sourceKind: String,
        targetKind: String,
    ): String {
        if (source.startsWith("entrypoint:")) return "MANIFEST"
        val combined = "$source $target".lowercase()
        if ("di" in combined || "hilt" in combined || sourceKind == "DI" || targetKind == "DI") return "DI_PROVIDES"
        if ((sourceKind == "UI" && targetKind in setOf("STATE", "REPO")) || (sourceKind == "STATE" && targetKind == "REPO")) {
            return "USES_TYPE"
        }
        return "IMPORT"
    }

    private fun edgePriority(
        source: String,
        target: String,
        sourceKind: String,
        targetKind: String,
        edgeType: String,
        focusPaths: Set<String>,
        focusEdges: Set<String>,
    ): Int {
        var score =
            when (edgeType) {
                "MANIFEST" -> 120
                "DI_PROVIDES" -> 90
                "USES_TYPE" -> 70
                else -> 40
            }
        if (sourceKind == "UI" && targetKind == "STATE") score += 20
        if (sourceKind == "STATE" && targetKind == "REPO") score += 18
        if (targetKind == "REPO") score += 8
        if (source.contains("entrypoint:")) score += 12
        if (sourceKind == "TEST" || targetKind == "TEST") score -= 28
        if (sourceKind == "CONFIG" && targetKind == "CONFIG") score -= 18
        if (sourceKind == "OTHER" && targetKind == "OTHER") score -= 10
        val sourceNorm = source.removePrefix("entrypoint:")
        val targetNorm = target.removePrefix("entrypoint:")
        if (sourceNorm in focusPaths || targetNorm in focusPaths) score += 60
        if ("$sourceNorm->$targetNorm" in focusEdges) score += 110
        return score
    }

    private fun inferKindFromPath(path: String): String {
        val lower = path.lowercase()
        return when {
            "/ui/" in lower || "activity" in lower || "fragment" in lower || "screen" in lower || "widget" in lower -> "UI"
            "/state/" in lower || "viewmodel" in lower || "bloc" in lower || "provider" in lower -> "STATE"
            "repository" in lower || "/repo/" in lower -> "REPO"
            "/di/" in lower || "module" in lower || "component" in lower -> "DI"
            "/data/" in lower || "/datasource/" in lower || "dao" in lower -> "DATA"
            else -> "OTHER"
        }
    }

    private fun inferPlatform(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".dart") || lower.startsWith("lib/") -> "flutter"
            lower.endsWith(".kt") || lower.endsWith(".java") || lower.contains("androidmanifest.xml") -> "android"
            else -> "shared"
        }
    }

    private fun labelFor(id: String): String = id.substringAfterLast('/').ifBlank { id }

    private fun groupFor(path: String): String {
        val lower = path.lowercase()
        if (
            lower.endsWith(".gradle") ||
            lower.endsWith(".gradle.kts") ||
            lower.endsWith("settings.gradle") ||
            lower.endsWith("settings.gradle.kts") ||
            lower.endsWith("androidmanifest.xml") ||
            "build/" in lower ||
            "config/" in lower
        ) {
            return "config:build"
        }

        val segments = path.split('/').filter { it.isNotBlank() }

        // Flutter: lib/features/<feature>/...
        val libIdx = segments.indexOf("lib")
        val featuresIdx = segments.indexOfFirst { it == "features" || it == "feature" }
        if (libIdx >= 0 && featuresIdx == libIdx + 1 && featuresIdx + 1 < segments.size) {
            return "feature:${segments[featuresIdx + 1]}"
        }

        // Android: package-derived grouping from .../(java|kotlin)/<package...>
        val pkgRootIdx = segments.indexOfFirst { it == "java" || it == "kotlin" }
        if (pkgRootIdx >= 0 && pkgRootIdx + 2 < segments.size) {
            val packageParts = segments.drop(pkgRootIdx + 1).take(3)
            if (packageParts.isNotEmpty()) {
                return "pkg:${packageParts.joinToString(".")}"
            }
        }

        // Generic fallback: folder grouping.
        return when {
            segments.size >= 3 -> "folder:${segments[0]}/${segments[1]}/${segments[2]}"
            segments.size >= 2 -> "folder:${segments[0]}/${segments[1]}"
            segments.isNotEmpty() -> "folder:${segments[0]}"
            else -> "root"
        }
    }

    private fun kindName(kind: FileKind): String {
        return when (kind) {
            FileKind.UI -> "UI"
            FileKind.STATE -> "STATE"
            FileKind.REPOSITORY -> "REPO"
            FileKind.DI -> "DI"
            FileKind.DATA_SOURCE -> "DATA"
            FileKind.SERVICE -> "SERVICE"
            FileKind.MODEL -> "MODEL"
            FileKind.CONFIG -> "CONFIG"
            FileKind.TEST -> "TEST"
            FileKind.OTHER -> "OTHER"
        }
    }

    private fun toRelative(
        path: String,
        root: String,
    ): String {
        val normalized = normalize(path)
        val rootPrefix = if (root.endsWith("/")) root else "$root/"
        return normalized.removePrefix(rootPrefix)
    }

    private fun normalize(path: String): String = path.replace('\\', '/')

    private data class RankedEdge(
        val edge: ScoredEdge,
        val score: Int,
    )

    private data class ScoredEdge(
        val source: String,
        val target: String,
        val type: String,
        val score: Int,
    )

    private fun CytoscapeGraphPayload.toJsonString(): String {
        val nodesJson = nodes.joinToString(prefix = "[", postfix = "]") { it.toJson() }
        val edgesJson = edges.joinToString(prefix = "[", postfix = "]") { it.toJson() }
        return buildString {
            append("{")
            append("\"version\":$version,")
            append("\"bounded\":$bounded,")
            append("\"nodeLimit\":$nodeLimit,")
            append("\"edgeLimit\":$edgeLimit,")
            append("\"droppedNodeCount\":$droppedNodeCount,")
            append("\"droppedEdgeCount\":$droppedEdgeCount,")
            append("\"nodes\":$nodesJson,")
            append("\"edges\":$edgesJson,")
            append("\"summary\":${json(summary)}")
            append("}")
        }
    }

    private fun CytoscapeGraphNode.toJson(): String {
        val signalsJson = signals.joinToString(prefix = "[", postfix = "]") { json(it) }
        return buildString {
            append("{")
            append("\"id\":${json(id)},")
            append("\"label\":${json(label)},")
            append("\"path\":${json(path)},")
            append("\"kind\":${json(kind)},")
            append("\"platform\":${json(platform)},")
            append("\"signals\":$signalsJson,")
            append("\"group\":${json(group)},")
            append("\"metrics\":{\"fanIn\":${metrics.fanIn},\"fanOut\":${metrics.fanOut}},")
            append("\"hotspotScore\":$hotspotScore")
            append("}")
        }
    }

    private fun CytoscapeGraphEdge.toJson(): String {
        return buildString {
            append("{")
            append("\"id\":${json(id)},")
            append("\"source\":${json(source)},")
            append("\"target\":${json(target)},")
            append("\"edgeType\":${json(edgeType)},")
            append("\"weight\":$weight,")
            append("\"count\":$count")
            append("}")
        }
    }

    private fun json(value: String): String {
        val escaped =
            buildString {
                value.forEach { ch ->
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
            }
        return "\"$escaped\""
    }

    companion object {
        private const val MAX_SIGNALS_PER_NODE = 8
        private const val MAX_HINTED_FOCUS_NODES = 60
    }
}
