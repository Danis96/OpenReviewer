package com.example.open.reviewer.tour.index

import com.example.open.reviewer.tour.model.OpenReviewrProjectPlatforms
import com.example.open.reviewer.tour.model.OpenReviewrTourStop

fun interface OpenReviewrTourIndexListener {
    fun onIndexUpdated(snapshot: OpenReviewrTourIndexSnapshot)
}

data class OpenReviewrTourIndexSnapshot(
    val stops: List<OpenReviewrTourStop>,
    val platforms: OpenReviewrProjectPlatforms,
    val isScanning: Boolean,
)
