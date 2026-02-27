package com.example.open.reviewer.tour.ui

import com.example.open.reviewer.tour.analysis.OpenReviewrTourAnalysisResult
import com.example.open.reviewer.tour.analysis.OpenReviewrTourAnalysisService
import com.example.open.reviewer.tour.analysis.OpenReviewrTourSummary
import com.example.open.reviewer.tour.index.OpenReviewrTourIndexListener
import com.example.open.reviewer.tour.index.OpenReviewrTourIndexService
import com.example.open.reviewer.tour.index.OpenReviewrTourIndexSnapshot
import com.example.open.reviewer.tour.model.MobilePlatform
import com.example.open.reviewer.tour.model.OpenReviewrProjectPlatforms
import com.example.open.reviewer.tour.model.OpenReviewrTourStop
import com.example.open.reviewer.tour.player.OpenReviewrTourPlayerController
import com.example.open.reviewer.tour.player.OpenReviewrTourPlayerState
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

class OpenReviewrToursPanel(
    private val project: Project,
) : JBPanel<OpenReviewrToursPanel>(BorderLayout()) {
    private val indexService = OpenReviewrTourIndexService.getInstance(project)
    private val analysisService = OpenReviewrTourAnalysisService.getInstance(project)

    private var latestSnapshot =
        OpenReviewrTourIndexSnapshot(
            emptyList(),
            OpenReviewrProjectPlatforms(emptySet()),
            false,
        )
    private var selectedStopIndex = 0
    private var isAnalyzing = false

    private val summaryByStopKey = linkedMapOf<String, CachedTourSummary>()
    private val errorByStopKey = linkedMapOf<String, String>()

    private val refreshButton = JButton(AllIcons.Actions.Refresh)
    private val startTourButton = JButton("Start Tour")
    private val exitTourButton = JButton("Exit Tour")
    private val analyzeSelectedButton = JButton("Analyze Selected")
    private val analyzeAllButton = JButton("Analyze All")
    private val statusLabel = JBLabel("Ready")
    private val loaderIcon = AsyncProcessIcon("openreviewr-tour-loader")
    private val floatingTourPanel =
        OpenReviewrTourFloatingPanel(
            onPrevious = { tourPlayerController.previousStep() },
            onNext = { tourPlayerController.nextStep() },
            onExit = { tourPlayerController.exitTour() },
        )
    private val tourPlayerController =
        OpenReviewrTourPlayerController(project) { state ->
            onTourStateChanged(state)
        }
    private var activeTourState: OpenReviewrTourPlayerState? = null

    private val filesChipPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply { isOpaque = false }
    private val filesChipScroll =
        JBScrollPane(filesChipPanel).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_NEVER
            preferredSize = Dimension(1, 42)
            maximumSize = Dimension(Int.MAX_VALUE, 42)
            minimumSize = Dimension(1, 42)
            isOpaque = false
            viewport.isOpaque = false
        }

    private val stopIconLabel = JBLabel()
    private val stopFileLabel = JBLabel("-")
    private val stopBadgeLabel = JBLabel("-")
    private val stopLineLabel = JBLabel("Line -")
    private val stopDescriptionLabel = JBLabel("No tour stops detected")
    private val openInEditorButton = PillButton("↗  Open")

    private val overviewArea = createBodyTextArea()
    private val analyzedAtLabel = JBLabel("")

    private val responsibilitiesPanel = JPanel()
    private val risksPanel = JPanel()
    private val relatedFilesPanel = JPanel()

    // GridBagLayout is the only reliable way to get full-width children in a vertical stack
    private val root =
        JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(12)
        }
    private var rootRow = 0

    init {
        border = JBUI.Borders.empty()

        configureStaticComponents()

        addRow(createActionsRow(), topGap = 0)
        addRow(createFilesSection(), topGap = 8)
        addRow(createStopCard(), topGap = 16) // increased from 8 → 16 for breathing room
        addRow(createSection("AI-Generated Overview", createOverviewCard()), topGap = 12)
        addRow(createSection("Key Responsibilities", createResponsibilitiesCard()), topGap = 10)
        addRow(createSection("Important Considerations", createRisksCard()), topGap = 10)
        addRow(createSection("Related Files", createRelatedFilesCard()), topGap = 10)
        // Push all rows to the top
        addFiller()

        val scroll = JBScrollPane(root)
        scroll.border = JBUI.Borders.empty()
        scroll.viewportBorder = JBUI.Borders.empty()
        scroll.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER

        add(scroll, BorderLayout.CENTER)

        val connection = project.messageBus.connect(project)
        connection.subscribe(
            OpenReviewrTourIndexService.TOPIC,
            OpenReviewrTourIndexListener { snapshot ->
                ApplicationManager.getApplication().invokeLater {
                    latestSnapshot = snapshot
                    if (selectedStopIndex >= snapshot.stops.size) {
                        selectedStopIndex = (snapshot.stops.size - 1).coerceAtLeast(0)
                    }
                    render()
                }
            },
        )

        refreshButton.addActionListener { indexService.refreshNow() }
        startTourButton.addActionListener { startTourFromSelection() }
        exitTourButton.addActionListener { tourPlayerController.exitTour() }
        analyzeSelectedButton.addActionListener { analyzeSelectedStop() }
        analyzeAllButton.addActionListener { analyzeAllStops() }
        openInEditorButton.addActionListener { openSelectedStopInEditor() }

        latestSnapshot = indexService.getSnapshot()
        render()
    }

    /** Adds a component as a full-width row using GridBagLayout (weightx=1, fill=HORIZONTAL). */
    private fun addRow(
        component: JComponent,
        topGap: Int = 0,
    ) {
        val gbc =
            GridBagConstraints().apply {
                gridx = 0
                gridy = rootRow++
                weightx = 1.0
                weighty = 0.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(topGap, 0, 0, 0)
            }
        root.add(component, gbc)
    }

    /** Adds an invisible filler that absorbs remaining vertical space. */
    private fun addFiller() {
        val filler = JPanel().apply { isOpaque = false }
        val gbc =
            GridBagConstraints().apply {
                gridx = 0
                gridy = rootRow++
                weightx = 1.0
                weighty = 1.0
                fill = GridBagConstraints.BOTH
            }
        root.add(filler, gbc)
    }

    private fun configureStaticComponents() {
        loaderIcon.isOpaque = false
        loaderIcon.suspend()

        refreshButton.apply {
            toolTipText = "Refresh tour scan"
            isContentAreaFilled = false
            isFocusPainted = false
            isOpaque = false
            border = JBUI.Borders.empty(2)
            margin = JBUI.insets(0)
            preferredSize = JBUI.size(20, 20)
            minimumSize = JBUI.size(20, 20)
            maximumSize = JBUI.size(20, 20)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        stopFileLabel.font = JBFont.label().deriveFont(Font.BOLD, 13f)
        stopBadgeLabel.font = JBFont.small().asBold()
        stopLineLabel.font = JBFont.small()
        stopLineLabel.foreground = UIUtil.getContextHelpForeground()
        stopDescriptionLabel.font = JBFont.label().asPlain()
        stopDescriptionLabel.foreground = UIUtil.getLabelForeground()

        openInEditorButton.toolTipText = "Open in Editor"

        analyzedAtLabel.font = JBFont.small()
        analyzedAtLabel.foreground = UIUtil.getContextHelpForeground()

        responsibilitiesPanel.layout = BoxLayout(responsibilitiesPanel, BoxLayout.Y_AXIS)
        responsibilitiesPanel.isOpaque = false

        risksPanel.layout = BoxLayout(risksPanel, BoxLayout.Y_AXIS)
        risksPanel.isOpaque = false

        relatedFilesPanel.layout = BoxLayout(relatedFilesPanel, BoxLayout.Y_AXIS)
        relatedFilesPanel.isOpaque = false
    }

    private fun createActionsRow(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(refreshButton)
            add(analyzeSelectedButton)
            add(analyzeAllButton)
            add(loaderIcon)
            add(statusLabel)
        }
    }

    private fun createTourActionsRow(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(startTourButton)
            add(exitTourButton)
        }
    }

    /** "Tour Files" heading + chip strip. */
    private fun createFilesSection(): JComponent {
        val label =
            JBLabel("Tour Files").apply {
                font = JBFont.label().asBold()
                foreground = UIUtil.getLabelForeground()
            }
        val panel = JPanel(BorderLayout(0, 6))
        panel.isOpaque = false
        panel.add(label, BorderLayout.NORTH)
        panel.add(filesChipScroll, BorderLayout.CENTER)
        panel.add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(6)
                add(createTourActionsRow(), BorderLayout.WEST)
            },
            BorderLayout.SOUTH,
        )
        return panel
    }

    private fun createStopCard(): JComponent {
        // ── Top row: icon + filename + badge  |  open button ──────────────────
        val leftFileRow =
            JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(stopIconLabel)
                add(stopFileLabel)
                add(stopBadgeLabel)
            }

        val rightFileRow =
            JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(openInEditorButton)
            }

        val fileRow =
            JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(leftFileRow, BorderLayout.WEST)
                add(rightFileRow, BorderLayout.EAST)
            }

        // ── Thin divider ─────────────────────────────────────────────────────
        val divider =
            object : JPanel() {
                init {
                    isOpaque = false
                    preferredSize = Dimension(1, 1)
                    maximumSize = Dimension(Int.MAX_VALUE, 1)
                }

                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.color = JBColor(Color(0xD2D7E1), Color(0x4A505A))
                    g2.fillRect(0, 0, width, 1)
                }
            }

        val dividerWrapper =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(10, 0, 8, 0)
                add(divider, BorderLayout.CENTER)
            }

        // ── Meta row: line number ─────────────────────────────────────────────
        val metaRow =
            JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(stopLineLabel)
            }

        // ── Description ──────────────────────────────────────────────────────
        val descWrapper =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(6)
                add(stopDescriptionLabel, BorderLayout.CENTER)
            }

        // ── Assemble vertically ───────────────────────────────────────────────
        val content =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(12, 14, 12, 14)
            }
        content.add(fileRow)
        content.add(dividerWrapper)
        content.add(metaRow)
        content.add(descWrapper)

        return RoundedPanel().apply {
            layout = BorderLayout()
            add(content, BorderLayout.CENTER)
        }
    }

    private fun createOverviewCard(): JComponent {
        val top = JPanel(BorderLayout())
        top.isOpaque = false
        top.add(analyzedAtLabel, BorderLayout.WEST)

        val content = JPanel(BorderLayout())
        content.isOpaque = false
        content.border = JBUI.Borders.empty(10)
        content.add(top, BorderLayout.NORTH)
        content.add(overviewArea, BorderLayout.CENTER)

        return RoundedPanel().apply {
            layout = BorderLayout()
            add(content, BorderLayout.CENTER)
        }
    }

    private fun createResponsibilitiesCard(): JComponent =
        RoundedPanel().apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(8)
            add(responsibilitiesPanel, BorderLayout.CENTER)
        }

    private fun createRisksCard(): JComponent =
        RoundedPanel().apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(8)
            add(risksPanel, BorderLayout.CENTER)
        }

    private fun createRelatedFilesCard(): JComponent =
        RoundedPanel().apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(8)
            add(relatedFilesPanel, BorderLayout.CENTER)
        }

    private fun createSection(
        title: String,
        body: JComponent,
    ): JComponent {
        val label =
            JBLabel(title).apply {
                font = JBFont.label().asBold()
                foreground = UIUtil.getLabelForeground()
            }
        val section = JPanel(BorderLayout(0, 6))
        section.isOpaque = false
        section.add(label, BorderLayout.NORTH)
        section.add(body, BorderLayout.CENTER)
        return section
    }

    private fun createBodyTextArea(): JTextArea {
        return JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.empty()
            font = JBFont.label().asPlain()
        }
    }

    private fun rebuildFileChips(stops: List<OpenReviewrTourStop>) {
        filesChipPanel.removeAll()

        val byFile = stops.groupBy { it.filePath }
        byFile.entries.forEach { (filePath, fileStops) ->
            val name = filePath.substringAfterLast('/')
            val button =
                JButton(name).apply {
                    margin = JBUI.insets(3, 10)
                    font = JBFont.small()
                    isFocusPainted = false
                    addActionListener {
                        val firstIndex = stops.indexOfFirst { it.filePath == filePath }
                        if (firstIndex >= 0) {
                            selectedStopIndex = firstIndex
                            render()
                        }
                    }
                    toolTipText = "${fileStops.size} tour stop(s) in $name"
                }
            filesChipPanel.add(button)
        }

        filesChipPanel.revalidate()
        filesChipPanel.repaint()
    }

    private fun render() {
        val stop = latestSnapshot.stops.getOrNull(selectedStopIndex)

        rebuildFileChips(latestSnapshot.stops)
        updateLoader()
        updateButtonsState(stop)
        updateStatusText()

        if (stop == null) {
            stopIconLabel.icon = null
            stopFileLabel.text = "No tour stops"
            stopBadgeLabel.text = ""
            stopLineLabel.text = ""
            stopDescriptionLabel.text = "Add @tour markers in code comments to create tour stops."
            analyzedAtLabel.text = ""
            overviewArea.text = "No AI summary yet."
            renderResponsibilities(emptyList())
            renderRisks(emptyList())
            renderRelatedFiles(emptyList())
            revalidate()
            repaint()
            return
        }

        stopIconLabel.icon = languageIconFor(stop)
        stopFileLabel.text = stop.filePath.substringAfterLast('/')
        stopBadgeLabel.text = platformLabel(stop.platform)
        stopBadgeLabel.foreground = platformColor(stop.platform)
        stopLineLabel.text = "Line ${stop.lineNumber}"
        stopDescriptionLabel.text = stop.description ?: "No description"

        val key = stopKey(stop)
        val cached = summaryByStopKey[key]
        val error = errorByStopKey[key]
        analyzedAtLabel.text = cached?.let { "Last analyzed: ${formatTimestamp(it.analyzedAtMillis)}" }.orEmpty()

        if (error != null) {
            overviewArea.text = error
            renderResponsibilities(emptyList())
            renderRisks(emptyList())
            renderRelatedFiles(emptyList())
            revalidate()
            repaint()
            return
        }

        if (cached == null) {
            overviewArea.text = "No AI summary yet. Click Analyze Selected or Analyze All."
            renderResponsibilities(emptyList())
            renderRisks(emptyList())
            renderRelatedFiles(emptyList())
            revalidate()
            repaint()
            return
        }

        overviewArea.text = cached.summary.summary
        renderResponsibilities(cached.summary.keyResponsibilities)
        renderRisks(cached.summary.risks.orEmpty())
        renderRelatedFiles(cached.summary.relatedFiles.orEmpty())
        revalidate()
        repaint()
    }

    private fun updateLoader() {
        val active = latestSnapshot.isScanning
        loaderIcon.isVisible = active
        if (active) loaderIcon.resume() else loaderIcon.suspend()
    }

    private fun updateButtonsState(selectedStop: OpenReviewrTourStop?) {
        val canAnalyze = latestSnapshot.platforms.isSupported && !latestSnapshot.isScanning && !isAnalyzing
        val isGuidedActive = activeTourState != null
        val hasAnalyzedStops = summaryByStopKey.isNotEmpty()
        refreshButton.isEnabled = !isAnalyzing && !isGuidedActive
        startTourButton.isVisible = !isGuidedActive
        startTourButton.isEnabled =
            !latestSnapshot.isScanning &&
            latestSnapshot.tours.isNotEmpty() &&
            hasAnalyzedStops &&
            !isGuidedActive
        exitTourButton.isVisible = isGuidedActive
        exitTourButton.isEnabled = isGuidedActive
        analyzeSelectedButton.isEnabled = canAnalyze && selectedStop != null
        analyzeAllButton.isEnabled = canAnalyze && latestSnapshot.stops.isNotEmpty()
        openInEditorButton.isEnabled = selectedStop != null
    }

    private fun updateStatusText() {
        statusLabel.text =
            when {
                activeTourState != null -> {
                    val state = activeTourState!!
                    "Guided mode: step ${state.currentStepIndex + 1}/${state.tour.stops.size}"
                }
                isAnalyzing -> "Analyzing..."
                latestSnapshot.isScanning -> "Scanning..."
                !latestSnapshot.platforms.isSupported -> "No supported mobile project detected"
                else -> "${latestSnapshot.stops.size} tour stop(s)"
            }
    }

    private fun analyzeSelectedStop() {
        val stop = latestSnapshot.stops.getOrNull(selectedStopIndex)
        if (stop == null) {
            notifyInfo("Select a tour stop first.")
            return
        }
        runAnalysis(listOf(stop), "Analyzing selected tour stop")
    }

    private fun analyzeAllStops() {
        val stops = latestSnapshot.stops
        if (stops.isEmpty()) {
            notifyInfo("No tour stops available to analyze.")
            return
        }
        runAnalysis(stops, "Analyzing all tour stops")
    }

    private fun runAnalysis(
        stops: List<OpenReviewrTourStop>,
        taskTitle: String,
    ) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, taskTitle, true) {
                override fun run(indicator: ProgressIndicator) {
                    setAnalyzing(true)
                    indicator.isIndeterminate = false
                    val total = stops.size.coerceAtLeast(1)
                    stops.forEachIndexed { index, stop ->
                        indicator.checkCanceled()
                        indicator.text = taskTitle
                        indicator.text2 = "${stop.filePath.substringAfterLast('/')} (${index + 1}/$total)"
                        indicator.fraction = index.toDouble() / total
                        applyAnalysisResult(analysisService.analyzeStop(stop, indicator))
                    }
                    indicator.fraction = 1.0
                }

                override fun onSuccess() {
                    setAnalyzing(false)
                    ApplicationManager.getApplication().invokeLater { render() }
                }

                override fun onCancel() {
                    setAnalyzing(false)
                    ApplicationManager.getApplication().invokeLater { render() }
                }

                override fun onThrowable(error: Throwable) {
                    setAnalyzing(false)
                    notifyError("Analysis failed: ${error.message ?: "unknown error"}")
                    ApplicationManager.getApplication().invokeLater { render() }
                }
            },
        )
    }

    private fun setAnalyzing(value: Boolean) {
        isAnalyzing = value
        ApplicationManager.getApplication().invokeLater { render() }
    }

    private fun applyAnalysisResult(result: OpenReviewrTourAnalysisResult) {
        val key = stopKey(result.stop)
        if (result.successful) {
            summaryByStopKey[key] = CachedTourSummary(result.summary!!, System.currentTimeMillis())
            errorByStopKey.remove(key)
        } else {
            summaryByStopKey.remove(key)
            errorByStopKey[key] = result.error ?: "Analysis failed"
        }
        activeTourState?.let { renderFloatingTourPanel(it) }
    }

    private fun startTourFromSelection() {
        val selectedStop = latestSnapshot.stops.getOrNull(selectedStopIndex)
        val selectedTour =
            selectedStop?.let { stop ->
                latestSnapshot.tours.firstOrNull { tour -> tour.stops.any { sameStop(it, stop) } }
            } ?: latestSnapshot.tours.firstOrNull()

        if (selectedTour == null) {
            notifyInfo("No tour available. Add @tour markers first.")
            return
        }

        val startIndex =
            selectedStop
                ?.let { stop -> selectedTour.stops.indexOfFirst { sameStop(it, stop) } }
                ?.takeIf { it >= 0 } ?: 0
        tourPlayerController.startTour(selectedTour, startIndex)
    }

    private fun onTourStateChanged(state: OpenReviewrTourPlayerState?) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            activeTourState = state
            if (state == null) {
                floatingTourPanel.close()
                render()
                return@invokeLater
            }

            val listIndex = latestSnapshot.stops.indexOfFirst { sameStop(it, state.currentStep ?: return@invokeLater) }
            if (listIndex >= 0) {
                selectedStopIndex = listIndex
            }
            render()
            renderFloatingTourPanel(state)
        }
    }

    private fun renderFloatingTourPanel(state: OpenReviewrTourPlayerState) {
        val step = state.currentStep ?: return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        floatingTourPanel.showOrUpdate(
            editor = editor,
            tourTitle = state.tour.name,
            stepNumber = state.currentStepIndex + 1,
            totalSteps = state.tour.stops.size,
            explanation = resolveStepExplanation(step),
            hasPrevious = state.hasPrevious,
            hasNext = state.hasNext,
        )
    }

    private fun resolveStepExplanation(stop: OpenReviewrTourStop): String {
        val cachedSummary = summaryByStopKey[stopKey(stop)]?.summary?.summary
        return stop.aiSummary ?: cachedSummary ?: stop.description ?: "No explanation available for this step yet."
    }

    private fun openSelectedStopInEditor() {
        val stop = latestSnapshot.stops.getOrNull(selectedStopIndex) ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(stop.filePath)
        if (file == null) {
            notifyError("Unable to open file: ${stop.filePath}")
            return
        }
        OpenFileDescriptor(project, file, (stop.lineNumber - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun renderResponsibilities(items: List<String>) {
        responsibilitiesPanel.removeAll()
        if (items.isEmpty()) {
            responsibilitiesPanel.add(fullWidthRow(createPlainRow("No responsibilities available.")))
        } else {
            items.forEach { responsibilitiesPanel.add(fullWidthRow(createSuccessRow(it))) }
        }
        responsibilitiesPanel.revalidate()
        responsibilitiesPanel.repaint()
    }

    private fun renderRisks(items: List<String>) {
        risksPanel.removeAll()
        if (items.isEmpty()) {
            risksPanel.add(fullWidthRow(createPlainRow("No critical considerations reported.")))
        } else {
            items.forEach { risksPanel.add(fullWidthRow(createWarningRow(it))) }
        }
        risksPanel.revalidate()
        risksPanel.repaint()
    }

    private fun renderRelatedFiles(items: List<String>) {
        relatedFilesPanel.removeAll()
        if (items.isEmpty()) {
            relatedFilesPanel.add(fullWidthRow(createPlainRow("No related files provided.")))
        } else {
            items.forEach { relatedFilesPanel.add(fullWidthRow(createRelatedFileRow(it))) }
        }
        relatedFilesPanel.revalidate()
        relatedFilesPanel.repaint()
    }

    /**
     * Wraps a card in a BorderLayout panel so it fills the full width of the
     * parent BoxLayout column — BorderLayout CENTER always stretches horizontally.
     */
    private fun fullWidthRow(card: JComponent): JComponent {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(8)
            add(card, BorderLayout.CENTER)
        }
    }

    private fun createPlainRow(text: String): JComponent {
        val area = rowTextArea(text, UIUtil.getLabelForeground())
        return RoundedPanel().apply {
            layout = BorderLayout()
            add(area, BorderLayout.CENTER)
        }
    }

    private fun createSuccessRow(text: String): JComponent {
        val area = rowTextArea("\u2713  $text", JBColor(Color(0x2A7A45), Color(0x65D996)))
        return RoundedPanel().apply {
            layout = BorderLayout()
            add(area, BorderLayout.CENTER)
        }
    }

    private fun createWarningRow(text: String): JComponent {
        val area = rowTextArea("!  $text", JBColor(Color(0x9A2E2E), Color(0xFF8A7A)))
        return RoundedPanel(
            fill = JBColor(Color(0xFFE7E7), Color(0x4A2C2C)),
            stroke = JBColor(Color(0xD9A0A0), Color(0x7A3B3B)),
        ).apply {
            layout = BorderLayout()
            add(area, BorderLayout.CENTER)
        }
    }

    private fun createRelatedFileRow(path: String): JComponent {
        // Use a clickable text area styled as a link
        val area = rowTextArea(path, JBColor(Color(0x2E6FD8), Color(0x79BAFF)))
        area.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        area.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = openPath(path)
            },
        )
        return RoundedPanel().apply {
            layout = BorderLayout()
            add(area, BorderLayout.CENTER)
        }
    }

    /** A read-only, transparent, wrapping text area used as a row label. */
    private fun rowTextArea(
        text: String,
        color: Color,
    ): JTextArea {
        return JTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            isFocusable = false
            border = JBUI.Borders.empty(8, 10)
            font = JBFont.label().asPlain()
            foreground = color
            // No fixed preferred width — lets the parent GridBag column drive width
        }
    }

    private fun openPath(path: String) {
        val absolute =
            if (path.startsWith('/')) {
                path
            } else {
                val base = project.basePath ?: ""
                if (base.isBlank()) path else "$base/$path"
            }
        val file = LocalFileSystem.getInstance().findFileByPath(absolute)
        if (file == null) {
            notifyError("Unable to open file: $path")
            return
        }
        OpenFileDescriptor(project, file, 0, 0).navigate(true)
    }

    private fun languageIconFor(stop: OpenReviewrTourStop) =
        FileTypeManager.getInstance().getFileTypeByFileName(stop.filePath.substringAfterLast('/')).icon

    private fun platformColor(platform: MobilePlatform): JBColor {
        return when (platform) {
            MobilePlatform.ANDROID -> JBColor(Color(0x1C8F4A), Color(0x62E38D))
            MobilePlatform.FLUTTER -> JBColor(Color(0x0D7CC1), Color(0x63C8FF))
            MobilePlatform.REACT_NATIVE -> JBColor(Color(0x2A74C6), Color(0x8EC9FF))
            MobilePlatform.IOS -> JBColor(Color(0x5D5D5D), Color(0xC8C8C8))
            MobilePlatform.UNKNOWN -> JBColor(Color(0x777777), Color(0xAAAAAA))
        }
    }

    private fun sameStop(
        left: OpenReviewrTourStop,
        right: OpenReviewrTourStop,
    ): Boolean {
        return left.filePath == right.filePath &&
            left.lineNumber == right.lineNumber &&
            left.description.orEmpty() == right.description.orEmpty()
    }

    private fun stopKey(stop: OpenReviewrTourStop): String = "${stop.filePath}:${stop.lineNumber}:${stop.description.orEmpty()}"

    private fun platformLabel(platform: MobilePlatform): String {
        return when (platform) {
            MobilePlatform.ANDROID -> "Android"
            MobilePlatform.FLUTTER -> "Flutter"
            MobilePlatform.REACT_NATIVE -> "React Native"
            MobilePlatform.IOS -> "iOS"
            MobilePlatform.UNKNOWN -> "Unknown"
        }
    }

    private fun formatTimestamp(timestampMillis: Long): String {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestampMillis))
    }

    private fun notifyInfo(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Open Reviewer Notifications")
            .createNotification("OpenReviewr Tours", message, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun notifyError(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Open Reviewer Notifications")
            .createNotification("OpenReviewr Tours", message, NotificationType.ERROR)
            .notify(project)
    }
}

private data class CachedTourSummary(
    val summary: OpenReviewrTourSummary,
    val analyzedAtMillis: Long,
)

/**
 * A borderless, pill-shaped button with a subtle hover fill.
 * No Swing chrome — no border, no focus ring, no default background.
 */
private class PillButton(text: String) : JComponent() {
    private var hovered = false
    private var pressed = false
    private var clickListener: (() -> Unit)? = null
    private val label = JBLabel(text).apply { font = JBFont.small().asBold() }

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isFocusable = false
        isOpaque = false
        layout = BorderLayout()
        border = JBUI.Borders.empty(4, 12)
        add(label, BorderLayout.CENTER)

        addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    pressed = false
                    repaint()
                }

                override fun mousePressed(e: MouseEvent) {
                    pressed = true
                    repaint()
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (hovered && isEnabled) clickListener?.invoke()
                    pressed = false
                    repaint()
                }
            },
        )
    }

    fun addActionListener(listener: () -> Unit) {
        clickListener = listener
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        label.foreground = if (enabled) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Hover/press fill
        val fillAlpha =
            when {
                !isEnabled -> 0.0f
                pressed -> 0.18f
                hovered -> 0.10f
                else -> 0.0f
            }
        if (fillAlpha > 0f) {
            g2.color = Color(1f, 1f, 1f, fillAlpha)
            g2.fillRoundRect(0, 0, width, height, height, height)
        }

        // Subtle pill border
        g2.color = JBColor(Color(0xC0C5CF), Color(0x555B66))
        g2.stroke = BasicStroke(1f)
        g2.drawRoundRect(0, 0, width - 1, height - 1, height, height)

        g2.dispose()
        super.paintComponent(g)
    }
}

private class RoundedPanel(
    private val fill: Color = JBColor(Color(0xF6F7F9), Color(0x353A40)),
    private val stroke: Color = JBColor(Color(0xD2D7E1), Color(0x4A505A)),
) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(graphics: Graphics) {
        val g2 = graphics.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = fill
        g2.fillRoundRect(0, 0, width - 1, height - 1, 16, 16)
        g2.color = stroke
        g2.stroke = BasicStroke(1f)
        g2.drawRoundRect(0, 0, width - 1, height - 1, 16, 16)
        g2.dispose()
        super.paintComponent(graphics)
    }
}
