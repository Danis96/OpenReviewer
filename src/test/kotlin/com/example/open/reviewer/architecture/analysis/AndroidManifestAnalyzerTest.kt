package com.example.open.reviewer.architecture.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class AndroidManifestAnalyzerTest {
    @Test
    fun `extracts components launcher activity and permissions`() {
        withTempRepo { root ->
            val manifest =
                write(
                    root,
                    "app/src/main/AndroidManifest.xml",
                    """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                        <uses-permission android:name="android.permission.INTERNET" />
                        <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
                        <application android:name=".App">
                            <activity android:name=".MainActivity" android:exported="true">
                                <intent-filter>
                                    <action android:name="android.intent.action.MAIN" />
                                    <category android:name="android.intent.category.LAUNCHER" />
                                </intent-filter>
                            </activity>
                            <service android:name=".SyncService" />
                            <receiver android:name=".BootReceiver" />
                        </application>
                    </manifest>
                    """.trimIndent(),
                )

            val result = AndroidManifestAnalyzer().analyze(listOf(manifest.toString()))
            assertEquals(1, result.analyzedFiles)
            assertEquals(0, result.unreadableFiles)
            val file = result.files.single()
            assertEquals(".MainActivity", file.launcherActivity)
            assertEquals(3, file.components.size)
            assertTrue(file.permissions.contains("android.permission.INTERNET"))
            assertTrue(file.permissions.contains("android.permission.ACCESS_NETWORK_STATE"))
            assertTrue(result.entrypoints().contains(".MainActivity"))
            assertTrue(result.entrypoints().contains(".SyncService"))
            assertTrue(result.entrypoints().contains(".BootReceiver"))
        }
    }

    private fun withTempRepo(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("android-manifest-analyzer-test")
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
