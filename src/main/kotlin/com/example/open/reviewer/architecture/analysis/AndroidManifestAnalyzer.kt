package com.example.open.reviewer.architecture.analysis

import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class ManifestComponent(
    val type: String,
    val name: String,
    val exported: Boolean?,
)

data class AndroidManifestFileFacts(
    val path: String,
    val components: List<ManifestComponent>,
    val launcherActivity: String?,
    val permissions: List<String>,
) {
    fun entrypoints(): List<String> {
        val values = mutableSetOf<String>()
        launcherActivity?.let { values += it }
        components
            .filter { it.type == "activity" || it.type == "receiver" || it.type == "service" }
            .map { it.name }
            .forEach { values += it }
        return values.toList().sorted()
    }
}

data class AndroidManifestAnalysisResult(
    val files: List<AndroidManifestFileFacts>,
    val analyzedFiles: Int,
    val unreadableFiles: Int,
) {
    fun entrypoints(): List<String> = files.flatMap { it.entrypoints() }.distinct().sorted()
}

class AndroidManifestAnalyzer {
    fun analyze(manifestPaths: List<String>): AndroidManifestAnalysisResult {
        val parsed = mutableListOf<AndroidManifestFileFacts>()
        var analyzed = 0
        var unreadable = 0

        manifestPaths.sorted().forEach { rawPath ->
            val path = Path.of(rawPath)
            if (!Files.isRegularFile(path)) {
                unreadable += 1
                return@forEach
            }
            val facts = runCatching { parse(path) }.getOrNull()
            if (facts == null) {
                unreadable += 1
            } else {
                analyzed += 1
                parsed += facts
            }
        }

        return AndroidManifestAnalysisResult(
            files = parsed.sortedBy { it.path },
            analyzedFiles = analyzed,
            unreadableFiles = unreadable,
        )
    }

    private fun parse(path: Path): AndroidManifestFileFacts {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        val builder = factory.newDocumentBuilder()
        val document = Files.newInputStream(path).use { builder.parse(it) }
        document.documentElement.normalize()

        val components = mutableListOf<ManifestComponent>()
        var launcherActivity: String? = null

        val appNodes = document.getElementsByTagName("application")
        if (appNodes.length > 0) {
            val appElement = appNodes.item(0) as? Element
            if (appElement != null) {
                extractComponents(appElement, "activity", components)
                extractComponents(appElement, "service", components)
                extractComponents(appElement, "receiver", components)
                extractComponents(appElement, "provider", components)
                launcherActivity = findLauncherActivity(appElement)
            }
        }

        val permissions =
            (0 until document.getElementsByTagName("uses-permission").length)
                .mapNotNull { index ->
                    val element = document.getElementsByTagName("uses-permission").item(index) as? Element ?: return@mapNotNull null
                    androidName(element)
                }
                .distinct()
                .sorted()

        return AndroidManifestFileFacts(
            path = path.toAbsolutePath().normalize().toString().replace('\\', '/'),
            components = components.sortedWith(compareBy<ManifestComponent> { it.type }.thenBy { it.name }),
            launcherActivity = launcherActivity,
            permissions = permissions,
        )
    }

    private fun extractComponents(
        appElement: Element,
        tag: String,
        out: MutableList<ManifestComponent>,
    ) {
        val nodes = appElement.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val name = androidName(element) ?: continue
            val exported = androidExported(element)
            out += ManifestComponent(type = tag, name = name, exported = exported)
        }
    }

    private fun findLauncherActivity(appElement: Element): String? {
        val activityNodes = appElement.getElementsByTagName("activity")
        for (i in 0 until activityNodes.length) {
            val activity = activityNodes.item(i) as? Element ?: continue
            val activityName = androidName(activity) ?: continue
            val filters = activity.getElementsByTagName("intent-filter")
            for (j in 0 until filters.length) {
                val filter = filters.item(j) as? Element ?: continue
                val hasMain =
                    (0 until filter.getElementsByTagName("action").length).any { idx ->
                        val action = filter.getElementsByTagName("action").item(idx) as? Element ?: return@any false
                        androidName(action) == "android.intent.action.MAIN"
                    }
                val hasLauncher =
                    (0 until filter.getElementsByTagName("category").length).any { idx ->
                        val cat = filter.getElementsByTagName("category").item(idx) as? Element ?: return@any false
                        androidName(cat) == "android.intent.category.LAUNCHER"
                    }
                if (hasMain && hasLauncher) return activityName
            }
        }
        return null
    }

    private fun androidName(element: Element): String? {
        return element.getAttribute("android:name").takeIf { it.isNotBlank() }
            ?: element.getAttributeNS("http://schemas.android.com/apk/res/android", "name").takeIf { it.isNotBlank() }
    }

    private fun androidExported(element: Element): Boolean? {
        val value =
            element.getAttribute("android:exported").takeIf { it.isNotBlank() }
                ?: element.getAttributeNS("http://schemas.android.com/apk/res/android", "exported").takeIf { it.isNotBlank() }
        return when (value?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}
