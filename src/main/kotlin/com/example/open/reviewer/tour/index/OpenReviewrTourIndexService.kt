package com.example.open.reviewer.tour.index

import com.example.open.reviewer.tour.detection.MobileProjectDetector
import com.example.open.reviewer.tour.model.OpenReviewrProjectPlatforms
import com.example.open.reviewer.tour.model.OpenReviewrTourConstants
import com.example.open.reviewer.tour.model.OpenReviewrTourStop
import com.example.open.reviewer.tour.scanner.OpenReviewrTourScanner
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic

@Service(Service.Level.PROJECT)
class OpenReviewrTourIndexService(
    private val project: Project,
) : Disposable {
    private val logger = Logger.getInstance(OpenReviewrTourIndexService::class.java)
    private val projectDetector = MobileProjectDetector()
    private val scanner = OpenReviewrTourScanner()

    private val lock = Any()
    private val debounceAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile
    private var stops: List<OpenReviewrTourStop> = emptyList()

    @Volatile
    private var platforms = OpenReviewrProjectPlatforms(setOf())

    @Volatile
    private var isScanning = false

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES, OpenReviewrVfsListener())
        scheduleRebuild(immediate = true)
    }

    fun getSnapshot(): OpenReviewrTourIndexSnapshot {
        synchronized(lock) {
            return OpenReviewrTourIndexSnapshot(stops = stops, platforms = platforms, isScanning = isScanning)
        }
    }

    fun refreshNow() {
        scheduleRebuild(immediate = true)
    }

    fun scheduleRebuild(immediate: Boolean = false) {
        val delayMs = if (immediate) 0 else OpenReviewrTourConstants.SCAN_DEBOUNCE_MILLIS
        debounceAlarm.cancelAllRequests()
        debounceAlarm.addRequest(
            {
                ProgressManager.getInstance().run(
                    object : Task.Backgroundable(project, "OpenReviewr Tours scan", true) {
                        override fun run(indicator: ProgressIndicator) {
                            setScanning(true)
                            runCatching {
                                val detectedPlatforms = projectDetector.detect(project)
                                val scannedStops =
                                    if (detectedPlatforms.isSupported) {
                                        scanner.scan(project, indicator)
                                    } else {
                                        emptyList()
                                    }
                                updateIndex(scannedStops, detectedPlatforms)
                            }.onFailure {
                                logger.warn("OpenReviewr tours scan failed", it)
                                updateIndex(emptyList(), OpenReviewrProjectPlatforms(setOf()))
                            }
                            setScanning(false)
                        }
                    },
                )
            },
            delayMs,
        )
    }

    private fun updateIndex(
        updatedStops: List<OpenReviewrTourStop>,
        updatedPlatforms: OpenReviewrProjectPlatforms,
    ) {
        synchronized(lock) {
            stops = updatedStops
            platforms = updatedPlatforms
        }
        publishUpdate()
    }

    private fun setScanning(value: Boolean) {
        synchronized(lock) {
            isScanning = value
        }
        publishUpdate()
    }

    private fun publishUpdate() {
        project.messageBus.syncPublisher(TOPIC).onIndexUpdated(getSnapshot())
    }

    override fun dispose() {
        debounceAlarm.cancelAllRequests()
    }

    private inner class OpenReviewrVfsListener : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
            if (events.any { shouldTriggerRebuild(it.file) }) {
                scheduleRebuild(immediate = false)
            }
        }

        private fun shouldTriggerRebuild(file: VirtualFile?): Boolean {
            file ?: return false
            if (file.isDirectory) return true

            val ext = file.extension?.lowercase().orEmpty()
            if (ext in OpenReviewrTourConstants.supportedSourceExtensions) return true

            return file.name in setOf("AndroidManifest.xml", "pubspec.yaml", "package.json", "Info.plist") ||
                file.path.endsWith(".xcodeproj")
        }
    }

    companion object {
        val TOPIC: Topic<OpenReviewrTourIndexListener> =
            Topic.create("OpenReviewrTourIndexUpdates", OpenReviewrTourIndexListener::class.java)

        fun getInstance(project: Project): OpenReviewrTourIndexService = project.getService(OpenReviewrTourIndexService::class.java)
    }
}
