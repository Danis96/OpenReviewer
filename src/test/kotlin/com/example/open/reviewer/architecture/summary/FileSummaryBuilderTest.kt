package com.example.open.reviewer.architecture.summary

import com.example.open.reviewer.architecture.model.FileFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSummaryBuilderTest {
    private val builder = FileSummaryBuilder()

    @Test
    fun `build is stable across runs for same input`() {
        val facts =
            FileFacts(
                lineCount = 42,
                classCount = 2,
                functionCount = 8,
                importCount = 6,
                signalTags = mutableListOf("repository", "state-management", "ui"),
            )
        val content =
            """
            import 'package:flutter/material.dart';
            import 'package:provider/provider.dart';
            class HomeViewModel extends ChangeNotifier {}
            class HomeScreen extends StatelessWidget {}
            void main() { runApp(const Placeholder()); }
            """.trimIndent()

        val first = builder.build("/tmp/app/lib/features/home/home_view_model.dart", facts, content)
        val second = builder.build("/tmp/app/lib/features/home/home_view_model.dart", facts, content)

        assertEquals(first, second)
    }

    @Test
    fun `build is token-efficient with bounded headline and key points`() {
        val facts =
            FileFacts(
                lineCount = 9000,
                classCount = 200,
                functionCount = 400,
                importCount = 180,
                signalTags = mutableListOf("viewmodel", "repository", "ui", "state-management", "extra"),
            )
        val content = ("@HiltAndroidApp @Module @Inject @Provides @Composable runApp() freezed json_serializable " + "x".repeat(5000))
        val summary = builder.build("/tmp/app/src/main/java/com/acme/di/VeryLargeAndComplexAppModule.kt", facts, content)

        assertTrue(summary.headline.length <= 120)
        assertTrue(summary.keyPoints.size <= 3)
        assertTrue(summary.keyPoints.all { it.length <= 100 })
    }
}
