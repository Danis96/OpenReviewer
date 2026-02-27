package com.example.open.reviewer.tour.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenReviewrTourContextBuilderTest {
    private val builder = OpenReviewrTourContextBuilder()

    @Test
    fun `returns full file when below soft cap`() {
        val lines = List(10) { index -> "line-${index + 1}" }

        val result = builder.trimLines(lines, markerLine = 5)

        assertEquals(10, result.size)
        assertEquals(1, result.first().number)
        assertEquals("line-1", result.first().content)
        assertEquals(10, result.last().number)
    }

    @Test
    fun `returns marker-centered window for large files`() {
        val lines = List(2_000) { index -> "line-${index + 1}" }

        val result = builder.trimLines(lines, markerLine = 1_500)

        assertEquals(OpenReviewrTourContextBuilder.WINDOW_LINE_CAP, result.size)
        assertTrue(result.first().number <= 1_500)
        assertTrue(result.last().number >= 1_500)
    }
}
