package com.example.open.reviewer.tour.scanner

import com.example.open.reviewer.tour.model.OpenReviewrTourConstants

class OpenReviewrTourMarkerParser {
    private val markerRegex = Regex(OpenReviewrTourConstants.MARKER_PATTERN)

    fun parseMarker(commentText: String): OpenReviewrTourMarkerMatch? {
        return commentText
            .lineSequence()
            .map(::normalizeLine)
            .firstNotNullOfOrNull { line ->
                val match = markerRegex.matchEntire(line) ?: return@firstNotNullOfOrNull null
                val rawDescription = match.groupValues.getOrNull(2)?.trim().orEmpty()
                OpenReviewrTourMarkerMatch(description = rawDescription.ifBlank { null })
            }
    }

    private fun normalizeLine(rawLine: String): String {
        val trimmed = rawLine.trim()
        val withoutPrefix =
            trimmed.replaceFirst(
                Regex("""^(//+|/\*+|\*+)\s*"""),
                "",
            )
        return withoutPrefix.removeSuffix("*/").trim()
    }
}

data class OpenReviewrTourMarkerMatch(
    val description: String?,
)
