package com.example.open.reviewer.tour.analysis

import com.example.open.reviewer.tour.model.OpenReviewrTourStop
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class OpenReviewrTourContextBuilder {
    fun buildContext(stop: OpenReviewrTourStop): String {
        val path = Paths.get(stop.filePath)
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return ""
        }

        val lines = runCatching { Files.readAllLines(path, StandardCharsets.UTF_8) }.getOrDefault(emptyList())
        if (lines.isEmpty()) return ""

        val contextLines = trimLines(lines, stop.lineNumber)
        return contextLines.joinToString("\n") { indexedLine ->
            indexedLine.number.toString().padStart(5, ' ') + " | " + indexedLine.content
        }
    }

    fun trimLines(
        allLines: List<String>,
        markerLine: Int,
    ): List<IndexedLine> {
        if (allLines.isEmpty()) return emptyList()

        if (allLines.size <= SOFT_LINE_CAP) {
            return allLines.mapIndexed { idx, content -> IndexedLine(idx + 1, content) }
        }

        val markerIndex = (markerLine - 1).coerceIn(0, allLines.lastIndex)
        val halfWindow = WINDOW_LINE_CAP / 2
        var start = (markerIndex - halfWindow).coerceAtLeast(0)
        var endExclusive = (start + WINDOW_LINE_CAP).coerceAtMost(allLines.size)

        if (endExclusive - start < WINDOW_LINE_CAP) {
            start = (endExclusive - WINDOW_LINE_CAP).coerceAtLeast(0)
        }

        var selected =
            (start until endExclusive).map { index ->
                IndexedLine(number = index + 1, content = allLines[index])
            }

        if (selected.size > HARD_LINE_CAP) {
            selected = selected.take(HARD_LINE_CAP)
        }

        return selected
    }

    data class IndexedLine(
        val number: Int,
        val content: String,
    )

    companion object {
        const val SOFT_LINE_CAP = 1_000
        const val WINDOW_LINE_CAP = 1_000
        const val HARD_LINE_CAP = 1_200
    }
}
