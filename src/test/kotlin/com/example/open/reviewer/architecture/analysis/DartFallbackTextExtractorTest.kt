package com.example.open.reviewer.architecture.analysis

import com.example.open.reviewer.architecture.analysis.extractors.DartExtractionMode
import com.example.open.reviewer.architecture.analysis.extractors.DartFallbackTextExtractor
import com.example.open.reviewer.architecture.analysis.extractors.DartSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class DartFallbackTextExtractorTest {
    @Test
    fun `fallback extractor returns usable signals with fallback mode`() {
        withTempRepo { root ->
            val file =
                write(
                    root,
                    "lib/main.dart",
                    """
                    import 'package:flutter/material.dart';
                    import 'package:provider/provider.dart';
                    import 'package:flutter_bloc/flutter_bloc.dart';
                    import 'package:flutter_riverpod/flutter_riverpod.dart';
                    import 'package:freezed_annotation/freezed_annotation.dart';
                    import 'package:json_annotation/json_annotation.dart';

                    @freezed
                    @JsonSerializable()
                    class UserState extends ChangeNotifier {}
                    class HomeCubit extends Cubit<int> {}
                    class App extends StatelessWidget {}
                    void main() { runApp(App()); }
                    """.trimIndent(),
                )

            val result = DartFallbackTextExtractor().extractFiles(listOf(file.toString()))
            val facts = result.files.single()

            assertEquals(DartExtractionMode.FALLBACK, facts.extractionMode)
            assertEquals(0.5, facts.confidence, 0.0)
            assertTrue(facts.hasMain)
            assertTrue(DartSignal.FLUTTER_WIDGET in facts.signals)
            assertTrue(DartSignal.FLUTTER_PROVIDER in facts.signals)
            assertTrue(DartSignal.FLUTTER_BLOC in facts.signals)
            assertTrue(DartSignal.FLUTTER_RIVERPOD in facts.signals)
            assertTrue(DartSignal.FLUTTER_JSON_SERIALIZABLE in facts.signals)
            assertTrue(DartSignal.FLUTTER_FREEZED in facts.signals)
        }
    }

    @Test
    fun `fallback extractor never crashes on malformed file`() {
        withTempRepo { root ->
            val file = root.resolve("lib/broken.dart")
            file.parent.createDirectories()
            file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28.toByte(), 0x00.toByte(), 0xFF.toByte()))

            val result = DartFallbackTextExtractor().extractFiles(listOf(file.toString()))
            assertTrue(result.files.isNotEmpty())
            assertEquals(DartExtractionMode.FALLBACK, result.files.single().extractionMode)
        }
    }

    private fun withTempRepo(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("dart-fallback-extractor-test")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun write(
        root: Path,
        relativePath: String,
        content: String,
    ): Path {
        val file = root.resolve(relativePath)
        file.parent?.createDirectories()
        file.writeText(content)
        return file
    }
}
