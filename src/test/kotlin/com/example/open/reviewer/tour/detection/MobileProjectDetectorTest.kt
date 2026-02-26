package com.example.open.reviewer.tour.detection

import com.example.open.reviewer.tour.model.MobilePlatform
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileProjectDetectorTest {
    private val detector = MobileProjectDetector()

    @Test
    fun `detects android and flutter signals`() {
        val detected =
            detector.detectFromPaths(
                listOf(
                    "/tmp/app/src/main/AndroidManifest.xml",
                    "/tmp/app/pubspec.yaml",
                ),
            )

        assertTrue(MobilePlatform.ANDROID in detected.detected)
        assertTrue(MobilePlatform.FLUTTER in detected.detected)
    }

    @Test
    fun `detects react native only when package json and react native hint exist`() {
        val detected =
            detector.detectFromPaths(
                listOf(
                    "/tmp/app/package.json",
                    "/tmp/app/node_modules/react-native/index.js",
                ),
            )

        assertTrue(MobilePlatform.REACT_NATIVE in detected.detected)
    }

    @Test
    fun `falls back to unknown when no signals exist`() {
        val detected = detector.detectFromPaths(listOf("/tmp/app/README.md"))

        assertTrue(MobilePlatform.UNKNOWN in detected.detected)
    }
}
