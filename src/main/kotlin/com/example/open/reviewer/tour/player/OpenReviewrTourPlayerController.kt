package com.example.open.reviewer.tour.player

import com.example.open.reviewer.tour.model.OpenReviewrTour
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

class OpenReviewrTourPlayerController(
    private val project: Project,
    private val onStateChanged: (OpenReviewrTourPlayerState?) -> Unit,
) : Disposable {
    private val highlightService = OpenReviewrTourHighlightService()
    private var state: OpenReviewrTourPlayerState? = null

    fun isActive(): Boolean = state != null

    fun currentState(): OpenReviewrTourPlayerState? = state

    fun startTour(
        tour: OpenReviewrTour,
        startStepIndex: Int = 0,
    ) {
        if (tour.stops.isEmpty()) {
            exitTour()
            return
        }

        state = OpenReviewrTourPlayerState(tour, startStepIndex).withStep(startStepIndex)
        navigateAndPublish()
    }

    fun nextStep() {
        val current = state ?: return
        if (!current.hasNext) return
        state = current.withStep(current.currentStepIndex + 1)
        navigateAndPublish()
    }

    fun previousStep() {
        val current = state ?: return
        if (!current.hasPrevious) return
        state = current.withStep(current.currentStepIndex - 1)
        navigateAndPublish()
    }

    fun exitTour() {
        highlightService.clear()
        state = null
        publishState(null)
    }

    private fun navigateAndPublish() {
        val currentState = state
        val step = currentState?.currentStep
        if (currentState == null || step == null) {
            exitTour()
            return
        }

        val file = LocalFileSystem.getInstance().findFileByPath(step.filePath)
        if (file == null) {
            publishState(currentState)
            return
        }

        val descriptor = OpenFileDescriptor(project, file, (step.lineNumber - 1).coerceAtLeast(0), 0)
        val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        if (editor != null) {
            highlightService.highlightLine(editor, step.lineNumber)
        }
        publishState(currentState)
    }

    private fun publishState(value: OpenReviewrTourPlayerState?) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            onStateChanged(value)
        }
    }

    override fun dispose() {
        exitTour()
    }
}
