package com.example.open.reviewer.tour.detection

import com.example.open.reviewer.tour.model.MobilePlatform
import com.example.open.reviewer.tour.model.OpenReviewrProjectPlatforms
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile

class MobileProjectDetector {
    fun detect(project: Project): OpenReviewrProjectPlatforms {
        val filePaths = mutableSetOf<String>()
        val packageJsonFiles = mutableListOf<VirtualFile>()
        val fileIndex = ProjectFileIndex.getInstance(project)
        fileIndex.iterateContent { virtualFile ->
            if (!virtualFile.isDirectory) {
                filePaths += virtualFile.path
                if (virtualFile.name == "package.json") {
                    packageJsonFiles += virtualFile
                }
            }
            true
        }
        val detected = detectFromPaths(filePaths).detected.toMutableSet()
        val hasReactNativeDependency = packageJsonFiles.any(::hasReactNativeDependency)
        if (hasReactNativeDependency) {
            detected += MobilePlatform.REACT_NATIVE
        } else if (MobilePlatform.REACT_NATIVE in detected) {
            detected -= MobilePlatform.REACT_NATIVE
        }
        if (detected.isEmpty()) {
            detected += MobilePlatform.UNKNOWN
        }
        return OpenReviewrProjectPlatforms(detected)
    }

    fun detectFromPaths(paths: Collection<String>): OpenReviewrProjectPlatforms {
        val normalized = paths.map { it.replace('\\', '/') }
        val detected = mutableSetOf<MobilePlatform>()

        if (normalized.any { it.endsWith("/AndroidManifest.xml") || it.endsWith("AndroidManifest.xml") }) {
            detected += MobilePlatform.ANDROID
        }

        if (normalized.any { it.endsWith("/pubspec.yaml") || it.endsWith("pubspec.yaml") }) {
            detected += MobilePlatform.FLUTTER
        }

        val hasPackageJson = normalized.any { it.endsWith("/package.json") || it.endsWith("package.json") }
        val hasReactNativeHint = normalized.any { it.contains("/react-native/") || it.contains("react-native") }
        if (hasPackageJson && hasReactNativeHint) {
            detected += MobilePlatform.REACT_NATIVE
        }

        if (normalized.any { it.endsWith(".xcodeproj") || it.endsWith("/Info.plist") || it.endsWith("Info.plist") }) {
            detected += MobilePlatform.IOS
        }

        if (detected.isEmpty()) {
            detected += MobilePlatform.UNKNOWN
        }

        return OpenReviewrProjectPlatforms(detected = detected)
    }

    private fun hasReactNativeDependency(file: VirtualFile): Boolean {
        return runCatching {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            "\"react-native\"" in content
        }.getOrDefault(false)
    }
}
