package com.example.open.reviewer.tour.player

import com.example.open.reviewer.tour.model.MobilePlatform
import com.example.open.reviewer.tour.model.OpenReviewrTour
import com.example.open.reviewer.tour.model.OpenReviewrTourStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenReviewrTourPlayerStateTest {
    @Test
    fun `withStep clamps to valid bounds`() {
        val tour = sampleTour(stops = 3)
        val state = OpenReviewrTourPlayerState(tour = tour, currentStepIndex = 0)

        assertEquals(0, state.withStep(-10).currentStepIndex)
        assertEquals(2, state.withStep(999).currentStepIndex)
    }

    @Test
    fun `hasPrevious and hasNext reflect step position`() {
        val tour = sampleTour(stops = 3)

        val first = OpenReviewrTourPlayerState(tour, 0)
        assertFalse(first.hasPrevious)
        assertTrue(first.hasNext)

        val middle = OpenReviewrTourPlayerState(tour, 1)
        assertTrue(middle.hasPrevious)
        assertTrue(middle.hasNext)

        val last = OpenReviewrTourPlayerState(tour, 2)
        assertTrue(last.hasPrevious)
        assertFalse(last.hasNext)
    }

    private fun sampleTour(stops: Int): OpenReviewrTour {
        return OpenReviewrTour(
            id = "sample",
            name = "Sample Tour",
            platform = MobilePlatform.ANDROID,
            stops =
                (0 until stops).map { index ->
                    OpenReviewrTourStop(
                        filePath = "/tmp/Sample$index.kt",
                        lineNumber = index + 1,
                        description = "Step $index",
                        platform = MobilePlatform.ANDROID,
                        order = index,
                    )
                },
        )
    }
}
