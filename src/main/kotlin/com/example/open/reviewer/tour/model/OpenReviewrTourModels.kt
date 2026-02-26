package com.example.open.reviewer.tour.model

enum class MobilePlatform {
    ANDROID,
    FLUTTER,
    REACT_NATIVE,
    IOS,
    UNKNOWN,
}

data class OpenReviewrTourStop(
    val filePath: String,
    val lineNumber: Int,
    val description: String?,
    val platform: MobilePlatform,
)

data class OpenReviewrProjectPlatforms(
    val detected: Set<MobilePlatform>,
) {
    val isSupported: Boolean
        get() = detected.any { it != MobilePlatform.UNKNOWN }
}

object OpenReviewrTourConstants {
    const val MARKER_PREFIX = "@OpenReviewrTour"
    const val LEGACY_MARKER_PREFIX = "@GenieTour"
    const val MAX_FILE_SIZE_BYTES = 1_000_000L
    const val SCAN_DEBOUNCE_MILLIS = 750
    const val MARKER_PATTERN = "^@(OpenReviewrTour|GenieTour)(?::\\s*(.*))?$"

    val supportedSourceExtensions =
        setOf(
            "kt",
            "kts",
            "java",
            "swift",
            "dart",
            "js",
            "jsx",
            "ts",
            "tsx",
        )

    val excludedPathSegments =
        setOf(
            "/build/",
            "/.gradle/",
            "/node_modules/",
            "/Pods/",
            "/DerivedData/",
            "/.dart_tool/",
            "/.idea/",
        )
}
