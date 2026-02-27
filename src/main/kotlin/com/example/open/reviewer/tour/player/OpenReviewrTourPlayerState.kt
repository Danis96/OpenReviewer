package com.example.open.reviewer.tour.player

import com.example.open.reviewer.tour.model.OpenReviewrTour
import com.example.open.reviewer.tour.model.OpenReviewrTourStop

data class OpenReviewrTourPlayerState(
    val tour: OpenReviewrTour,
    val currentStepIndex: Int,
) {
    val currentStep: OpenReviewrTourStop?
        get() = tour.stops.getOrNull(currentStepIndex)

    val hasPrevious: Boolean
        get() = currentStepIndex > 0

    val hasNext: Boolean
        get() = currentStepIndex < tour.stops.lastIndex

    fun withStep(stepIndex: Int): OpenReviewrTourPlayerState {
        val bounded = stepIndex.coerceIn(0, tour.stops.lastIndex.coerceAtLeast(0))
        return copy(currentStepIndex = bounded)
    }
}
