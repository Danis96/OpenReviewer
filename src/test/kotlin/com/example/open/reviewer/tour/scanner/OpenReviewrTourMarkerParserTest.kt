package com.example.open.reviewer.tour.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpenReviewrTourMarkerParserTest {
    private val parser = OpenReviewrTourMarkerParser()

    @Test
    fun `parses marker with description`() {
        val marker = parser.parseMarker("// @tour: App entry point")

        assertNotNull(marker)
        assertEquals("App entry point", marker?.description)
    }

    @Test
    fun `parses marker without description`() {
        val marker = parser.parseMarker("// @tour")

        assertNotNull(marker)
        assertNull(marker?.description)
    }

    @Test
    fun `supports legacy openreviewr marker`() {
        val marker = parser.parseMarker("// @OpenReviewrTour: Legacy marker")

        assertNotNull(marker)
        assertEquals("Legacy marker", marker?.description)
    }

    @Test
    fun `supports legacy marker`() {
        val marker = parser.parseMarker("// @GenieTour: Legacy marker")

        assertNotNull(marker)
        assertEquals("Legacy marker", marker?.description)
    }

    @Test
    fun `parses dart doc style marker`() {
        val marker = parser.parseMarker("/// @tour: Root widget")

        assertNotNull(marker)
        assertEquals("Root widget", marker?.description)
    }

    @Test
    fun `rejects invalid marker`() {
        val marker = parser.parseMarker("// OpenReviewrTour")

        assertNull(marker)
    }
}
