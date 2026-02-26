package com.example.open.reviewer.analysis.scanner

import com.example.open.reviewer.analysis.FindingSeverity
import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRiskScannerTest {
    @Test
    fun `detects blocking call in entry function`() {
        val file = LightVirtualFile("MainActivity.kt", "")
        val node =
            ScannerNode(
                functionName = "onCreate",
                functionBody =
                    """
                    fun onCreate() {
                        Thread.sleep(100)
                    }
                    """.trimIndent(),
                file = file,
                functionLine = 1,
                depth = 0,
            )

        val findings = StartupRiskScanner().scanNodesForTest(listOf(node))

        assertTrue(findings.any { it.severity == FindingSeverity.CRITICAL && it.title.contains("Blocking") })
    }

    @Test
    fun `detects network call pattern`() {
        val file = LightVirtualFile("MainActivity.kt", "")
        val node =
            ScannerNode(
                functionName = "loadRemoteConfig",
                functionBody =
                    """
                    fun loadRemoteConfig() {
                        val client = HttpClient()
                    }
                    """.trimIndent(),
                file = file,
                functionLine = 10,
                depth = 1,
            )

        val findings = StartupRiskScanner().scanNodesForTest(listOf(node))

        assertTrue(findings.any { it.title.contains("Network call") && it.severity == FindingSeverity.WARN })
    }
}
