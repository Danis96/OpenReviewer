package com.example.open.reviewer.tour.analysis

import com.example.open.reviewer.tour.model.OpenReviewrTourStop

data class OpenReviewrTourSummary(
    val summary: String,
    val keyResponsibilities: List<String>,
    val risks: List<String>?,
    val relatedFiles: List<String>?,
)

data class OpenReviewrTourAnalysisResult(
    val stop: OpenReviewrTourStop,
    val summary: OpenReviewrTourSummary?,
    val error: String? = null,
) {
    val successful: Boolean
        get() = summary != null && error == null
}
