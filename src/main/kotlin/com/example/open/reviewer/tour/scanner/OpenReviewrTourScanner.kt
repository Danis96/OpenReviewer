package com.example.open.reviewer.tour.scanner

import com.example.open.reviewer.tour.model.MobilePlatform
import com.example.open.reviewer.tour.model.OpenReviewrTourConstants
import com.example.open.reviewer.tour.model.OpenReviewrTourStop
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

class OpenReviewrTourScanner(
    private val markerParser: OpenReviewrTourMarkerParser = OpenReviewrTourMarkerParser(),
) {
    fun scan(
        project: Project,
        indicator: ProgressIndicator,
    ): List<OpenReviewrTourStop> {
        val foundStops = mutableListOf<OpenReviewrTourStop>()
        val fileIndex = ProjectFileIndex.getInstance(project)

        indicator.text = "Scanning for @tour markers"
        indicator.isIndeterminate = true

        fileIndex.iterateContent { virtualFile ->
            indicator.checkCanceled()

            if (!isScannable(virtualFile.path, virtualFile.extension, virtualFile.isDirectory, virtualFile.length)) {
                return@iterateContent true
            }

            // zato sto mora biti u safe threadu,
            // scanner works in BG so we need to wrap psi manager
            val stopsInFile =
                ReadAction.compute<List<OpenReviewrTourStop>, RuntimeException> {
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@compute emptyList()
                    extractStops(psiFile)
                }

            if (stopsInFile.isNotEmpty()) {
                foundStops += stopsInFile
            }
            true
        }

        return foundStops.sortedWith(compareBy<OpenReviewrTourStop> { it.filePath }.thenBy { it.lineNumber })
    }

    private fun extractStops(psiFile: PsiFile): List<OpenReviewrTourStop> {
        val document = psiFile.viewProvider.document ?: return emptyList()
        val comments = PsiTreeUtil.collectElementsOfType(psiFile, PsiComment::class.java)

        return comments.mapNotNull { comment ->
            val markerMatch = markerParser.parseMarker(comment.text) ?: return@mapNotNull null
            val lineNumber = resolveLineNumber(document, comment.textOffset)
            OpenReviewrTourStop(
                filePath = psiFile.virtualFile.path,
                lineNumber = lineNumber,
                description = markerMatch.description,
                platform = inferPlatform(psiFile.virtualFile.path),
            )
        }
    }

    private fun resolveLineNumber(
        document: Document,
        offset: Int,
    ): Int {
        val normalizedOffset = offset.coerceIn(0, document.textLength.coerceAtLeast(0))
        return document.getLineNumber(normalizedOffset) + 1
    }

    private fun inferPlatform(path: String): MobilePlatform {
        val normalized = path.lowercase()
        return when {
            normalized.endsWith(".kt") ||
                normalized.endsWith(".kts") ||
                normalized.endsWith(".java") -> MobilePlatform.ANDROID
            normalized.endsWith(".dart") -> MobilePlatform.FLUTTER
            normalized.endsWith(".swift") ||
                normalized.endsWith(".m") ||
                normalized.endsWith(".mm") -> MobilePlatform.IOS
            normalized.endsWith(".ts") ||
                normalized.endsWith(".tsx") ||
                normalized.endsWith(".js") ||
                normalized.endsWith(".jsx") -> MobilePlatform.REACT_NATIVE
            else -> MobilePlatform.UNKNOWN
        }
    }

    private fun isScannable(
        path: String,
        extension: String?,
        isDirectory: Boolean,
        sizeBytes: Long,
    ): Boolean {
        if (isDirectory) return false
        if (sizeBytes > OpenReviewrTourConstants.MAX_FILE_SIZE_BYTES) return false

        val normalizedPath = path.replace('\\', '/')
        if (OpenReviewrTourConstants.excludedPathSegments.any { normalizedPath.contains(it) }) return false

        val ext = extension?.lowercase() ?: return false
        return ext in OpenReviewrTourConstants.supportedSourceExtensions
    }
}
