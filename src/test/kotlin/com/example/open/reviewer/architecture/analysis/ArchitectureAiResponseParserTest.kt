package com.example.open.reviewer.architecture.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureAiResponseParserTest {
    private val parser = ArchitectureAiResponseParser()

    @Test
    fun `parses strict valid json`() {
        val raw =
            """
            {
              "schemaVersion": 1,
              "architectureGuess": "MVVM",
              "confidence": 0.83,
              "patterns": [
                {
                  "name": "MVVM",
                  "confidence": 0.83,
                  "evidencePaths": ["app/src/main/java/com/example/ui/MainActivity.kt"]
                }
              ]
            }
            """.trimIndent()

        val result = parser.parse(raw)
        assertTrue(result.parsed)
        assertTrue(result.errors.isEmpty())
        assertTrue(result.response != null)
    }

    @Test
    fun `accepts markdown fenced json and still validates strictly`() {
        val raw =
            """
            ```json
            {
              "schemaVersion": 1,
              "architectureGuess": "Clean Architecture",
              "confidence": 0.79,
              "patterns": [
                {
                  "name": "clean architecture",
                  "confidence": 0.79,
                  "evidencePaths": ["app/src/main/java/com/example/domain/usecase/LoginUseCase.kt"]
                }
              ]
            }
            ```
            """.trimIndent()

        val result = parser.parse(raw)
        assertTrue(result.parsed)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `rejects invalid confidence and bad evidence paths without crashing`() {
        val raw =
            """
            {
              "schemaVersion": 1,
              "architectureGuess": "Something",
              "confidence": 1.7,
              "patterns": [
                {
                  "name": "something",
                  "confidence": -0.2,
                  "evidencePaths": ["../secrets.txt", "https://example.com/a.kt"]
                }
              ]
            }
            """.trimIndent()

        val result = parser.parse(raw)
        assertFalse(result.parsed)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `never crashes on malformed ai output`() {
        val raw = """```json { "schemaVersion": 1, "patterns": [ } ```"""
        val result = parser.parse(raw)
        assertFalse(result.parsed)
        assertTrue(result.response == null)
    }
}
