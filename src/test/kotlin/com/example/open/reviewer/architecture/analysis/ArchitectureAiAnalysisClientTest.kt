package com.example.open.reviewer.architecture.analysis

import com.example.open.reviewer.settings.AiProvider
import com.example.open.reviewer.settings.OpenReviewerSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class ArchitectureAiAnalysisClientTest {
    @Test
    fun `retries transient failures and succeeds`() {
        val attempts = AtomicInteger(0)
        val client =
            ArchitectureAiAnalysisClient(
                config = ArchitectureAiClientConfig(requestTimeout = Duration.ofMillis(200), maxRetries = 2, initialBackoffMillis = 1),
                settingsProvider = { settings(model = "model-a") },
                gateway =
                    object : ArchitectureAiGateway {
                        override fun analyze(
                            prompt: String,
                            settings: OpenReviewerSettingsState,
                        ): String {
                            val attempt = attempts.incrementAndGet()
                            if (attempt == 1) throw IllegalStateException("Suggestion request failed with status 503.")
                            return """
                            {
                              "schemaVersion": 1,
                              "architectureGuess": "MVVM",
                              "confidence": 0.82,
                              "patterns": [
                                {
                                  "name": "mvvm architecture",
                                  "confidence": 0.82,
                                  "evidencePaths": ["app/src/main/java/com/example/ui/MainActivity.kt"]
                                }
                              ]
                            }
                            """.trimIndent()
                        }
                    },
                sleeper = { },
            )

        val result = client.analyze(request = request(), checkCanceled = { })
        assertTrue(result.used)
        assertTrue(result.parsed)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `times out and fails robustly`() {
        val client =
            ArchitectureAiAnalysisClient(
                config = ArchitectureAiClientConfig(requestTimeout = Duration.ofMillis(30), maxRetries = 1, initialBackoffMillis = 1),
                settingsProvider = { settings() },
                gateway =
                    object : ArchitectureAiGateway {
                        override fun analyze(
                            prompt: String,
                            settings: OpenReviewerSettingsState,
                        ): String {
                            Thread.sleep(200)
                            return "late"
                        }
                    },
                sleeper = { },
            )

        val result = client.analyze(request = request(), checkCanceled = { })
        assertFalse(result.used)
        assertTrue(result.summary.contains("failed after"))
    }

    @Test(expected = CancellationException::class)
    fun `supports cancellation checks`() {
        val client =
            ArchitectureAiAnalysisClient(
                config = ArchitectureAiClientConfig(requestTimeout = Duration.ofMillis(100), maxRetries = 0, initialBackoffMillis = 1),
                settingsProvider = { settings() },
                gateway =
                    object : ArchitectureAiGateway {
                        override fun analyze(
                            prompt: String,
                            settings: OpenReviewerSettingsState,
                        ): String = """
                        {
                          "schemaVersion": 1,
                          "architectureGuess": "MVVM",
                          "confidence": 0.70,
                          "patterns": [
                            {
                              "name": "MVVM",
                              "confidence": 0.70,
                              "evidencePaths": ["app/src/main/java/com/example/vm/MainViewModel.kt"]
                            }
                          ]
                        }
                        """.trimIndent()
                    },
            )

        client.analyze(
            request = request(),
            checkCanceled = { throw CancellationException("cancel") },
        )
    }

    @Test
    fun `uses configured model in prompt`() {
        var capturedPrompt = ""
        val client =
            ArchitectureAiAnalysisClient(
                config = ArchitectureAiClientConfig(requestTimeout = Duration.ofMillis(100), maxRetries = 0, initialBackoffMillis = 1),
                settingsProvider = { settings(model = "my-model-42") },
                gateway =
                    object : ArchitectureAiGateway {
                        override fun analyze(
                            prompt: String,
                            settings: OpenReviewerSettingsState,
                        ): String {
                            capturedPrompt = prompt
                            return """
                            {
                              "schemaVersion": 1,
                              "architectureGuess": "Clean Architecture",
                              "confidence": 0.76,
                              "patterns": [
                                {
                                  "name": "clean architecture",
                                  "confidence": 0.76,
                                  "evidencePaths": ["app/src/main/java/com/example/domain/usecase/LoginUseCase.kt"]
                                }
                              ]
                            }
                            """.trimIndent()
                        }
                    },
            )

        val result = client.analyze(request = request(), checkCanceled = { })
        assertTrue(result.used)
        assertTrue(capturedPrompt.contains("Model: my-model-42"))
    }

    private fun settings(model: String = "gpt-test"): OpenReviewerSettingsState {
        return OpenReviewerSettingsState(
            provider = AiProvider.OPENAI,
            apiKey = "test-key",
            model = model,
            endpoint = "",
            includeCodeContext = false,
        )
    }

    private fun request(): ArchitectureAiRequest {
        return ArchitectureAiRequest(
            repoAggregate =
                RepoAggregatePayload(
                    tokenBudget = 900,
                    estimatedTokens = 120,
                    bounded = true,
                    signalCounts = listOf("ui:10", "viewmodel:6"),
                    representativeFiles = listOf("app/ui/MainActivity.kt [L100 C1 F6] {ui|viewmodel}"),
                    folderStructureHints = listOf("app/src/main:12"),
                    trimmedEdgeGraph = listOf("app/ui/MainActivity.kt -> app/vm/MainViewModel.kt"),
                ),
            preliminaryTopPattern = "MVVM",
            preliminaryTopConfidence = 0.82,
        )
    }
}
