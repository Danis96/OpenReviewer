package com.example.open.reviewer.architecture.ui

import com.example.open.reviewer.architecture.analysis.ArchitectureBackgroundAnalysisPipeline
import com.example.open.reviewer.architecture.analysis.ArchitecturePipelineProgress
import com.example.open.reviewer.architecture.analysis.ArchitecturePipelineStage
import com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayloadBuilder
import com.example.open.reviewer.architecture.analysis.PreliminaryArchitectureScorer
import com.example.open.reviewer.architecture.cache.ArchitectureFileCacheService
import com.example.open.reviewer.architecture.cache.CacheAnalysisRunResult
import com.example.open.reviewer.architecture.model.PreliminaryArchitectureGuess
import com.example.open.reviewer.architecture.scanner.FullProjectFileScanner
import com.example.open.reviewer.architecture.scanner.FullProjectScanResult
import com.example.open.reviewer.architecture.scanner.HighSignalFileLocator
import com.example.open.reviewer.architecture.scanner.LightweightSignalExtractor
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.math.min
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JSlider
import javax.swing.JTable
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.event.ChangeListener
import javax.swing.table.DefaultTableModel
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

class ArchitectureFastScanPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val logger = Logger.getInstance(ArchitectureFastScanPanel::class.java)
    private val locator = HighSignalFileLocator()
    private val extractor = LightweightSignalExtractor()
    private val deepScanner = FullProjectFileScanner()
    private val scorer = PreliminaryArchitectureScorer()
    private val fileCache = ArchitectureFileCacheService.getInstance(project)
    private val pipeline = ArchitectureBackgroundAnalysisPipeline(deepScanner, fileCache)
    private val graphPayloadBuilder = CytoscapeGraphPayloadBuilder()

    private val statusLabel =
        JBLabel("Open Architecture tab to run fast scan.").apply {
            foreground = UIUtil.getContextHelpForeground()
        }
    private val scanButton = JButton("Run Fast Scan", AllIcons.Actions.Execute)
    private val clearCacheButton = JButton("Clear Cache", AllIcons.Actions.GC)
    private val bannerLabel =
        JBLabel("⚡ Preliminary architecture detected... Deep analysis running.").apply {
            font = JBFont.label().asBold()
            foreground = UIUtil.getContextHelpForeground()
        }
    private val deepStatusLabel =
        JBLabel("Waiting for scan...").apply {
            foreground = UIUtil.getContextHelpForeground()
        }
    private val patternsPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
        }
    private val chipsPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
        }
    private val keySignalsArea =
        JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBFont.small()
            border = JBUI.Borders.empty(8)
            text = "Key signals will appear after fast scan."
        }
    private val overviewSummaryArea =
        JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBFont.small()
            border = JBUI.Borders.empty(10)
            text = "Overview appears after fast scan."
        }
    private val highLevelDiagramArea =
        JBTextArea().apply {
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            font = JBFont.small()
            border = JBUI.Borders.empty(10)
            text = "High-level diagram appears after fast scan."
        }
    private val evidenceArea =
        JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBFont.small()
            border = JBUI.Borders.empty(10)
            text = "Evidence appears after fast scan."
        }
    private val evidenceTableModel =
        object : DefaultTableModel(arrayOf("Pattern", "Confidence", "Signals", "Files"), 0) {
            override fun isCellEditable(
                row: Int,
                column: Int,
            ): Boolean = false
        }
    private val evidenceTable =
        JTable(evidenceTableModel).apply {
            autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
            tableHeader.reorderingAllowed = false
            rowHeight = 24
        }
    private val graphArea =
        JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBFont.small()
            border = JBUI.Borders.empty(10)
            text = "Graph view appears after fast scan."
        }
    private val graphRendererStatusLabel =
        JBLabel("Renderer: checking...").apply {
            foreground = UIUtil.getContextHelpForeground()
        }
    private val graphLayoutSelector = JComboBox(arrayOf("architecture", "cose", "cose-bilkent"))
    private val architectureViewSelector =
        JComboBox(
            arrayOf(
                "View: Layered",
                "View: MVVM",
                "View: Clean",
                "View: State Mgmt",
                "View: Feature Topology",
            ),
        )
    private val fitGraphButton = JButton("Fit")
    private val resetGraphButton = JButton("Reset")
    private val rerunLayoutButton = JButton("Re-run layout")
    private val collapseAllGraphButton = JButton("Collapse all")
    private val kindFilterSelector = JComboBox(arrayOf("Kind: ALL", "UI", "STATE", "REPO", "DI", "MODEL", "CONFIG", "TEST"))
    private val signalFilterSelector =
        JComboBox(arrayOf("Signal: ALL", "HILT", "ROOM", "RETROFIT", "COMPOSE", "PROVIDER", "BLOC", "RIVERPOD"))
    private val platformFilterSelector = JComboBox(arrayOf("Platform: ALL", "android", "flutter"))
    private val edgeTypeFilterSelector = JComboBox(arrayOf("Edge: ALL", "IMPORT", "USES_TYPE", "DI_PROVIDES", "MANIFEST"))
    private val depthSlider =
        JSlider(1, 5, 2).apply {
            majorTickSpacing = 1
            paintTicks = true
            snapToTicks = true
            toolTipText = "Depth"
        }
    private val fromSelectedCheck = JCheckBox("From selected", false)
    private val pathHighlightModeCheck = JCheckBox("Path mode", false)
    private val nodeDetailsTitle = JBLabel("Node details: none")
    private val nodeDetailsArea =
        JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBFont.small()
            border = JBUI.Borders.empty(8)
            text = "Select a node in graph to inspect details."
        }
    private val openNodeFileButton = JButton("Open file")
    private val focusNodeButton = JButton("Focus node")
    private val showLayerPathButton = JButton("Show layer path")
    private val hotspotsPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 0, 0)
        }
    private val graphCardLayout = CardLayout()
    private val graphContentPanel = JPanel(graphCardLayout)
    private var graphBrowser: JBCefBrowser? = null
    private var graphJsQuery: JBCefJSQuery? = null
    private var graphRendererReady = false
    private val queuedGraphCommands = ArrayDeque<String>()
    private var lastGraphSummaryText = "Graph view appears after fast scan."
    private var lastGraphPayloadJson = """{"version":1,"nodes":[],"edges":[],"summary":"Graph view appears after fast scan."}"""
    private var lastBaseGraphPayload: com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload? = null
    private var lastRenderedGraphPayload: com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload? = null
    private var latestFileAnalysisByRelative = emptyMap<String, com.example.open.reviewer.architecture.cache.CachedFileAnalysis>()
    private var selectedGraphNodeId: String? = null
    private var lastLayout = "architecture"
    private val graphFallbackPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(
                JBLabel("JCEF unavailable or disabled. Showing fallback graph text view.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                },
                BorderLayout.NORTH,
            )
            add(JBScrollPane(graphArea), BorderLayout.CENTER)
        }

    @Volatile
    private var scanInProgress = false

    @Volatile
    private var autoTriggeredOnce = false

    init {
        border = JBUI.Borders.empty(8)

        val top = JPanel(BorderLayout()).apply { isOpaque = false }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(6, 0, 8, 0)
            add(bannerLabel)
            add(deepStatusLabel)
        }
        val keySignalsContainer = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(JBLabel("Key signals").apply { font = JBFont.label().asBold() }, BorderLayout.NORTH)
            add(JBScrollPane(keySignalsArea).apply { preferredSize = java.awt.Dimension(0, 120) }, BorderLayout.CENTER)
        }
        val patternContainer = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("Guessed patterns").apply { font = JBFont.label().asBold() }, BorderLayout.NORTH)
            add(patternsPanel, BorderLayout.CENTER)
        }
        val overviewTab = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(patternContainer)
            add(JBLabel("Key chips").apply { font = JBFont.label().asBold() })
            add(chipsPanel)
            add(keySignalsContainer)
            add(JBLabel("High-level diagram").apply {
                font = JBFont.label().asBold()
                border = JBUI.Borders.emptyTop(8)
            })
            add(JBScrollPane(highLevelDiagramArea).apply { preferredSize = java.awt.Dimension(0, 130) })
            add(JBLabel("Overview summary").apply {
                font = JBFont.label().asBold()
                border = JBUI.Borders.emptyTop(8)
            })
            add(JBScrollPane(overviewSummaryArea).apply { preferredSize = java.awt.Dimension(0, 180) })
        }
        val evidenceTab = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(JBLabel("Evidence Table (AI claims)"), BorderLayout.NORTH)
            add(JBScrollPane(evidenceTable), BorderLayout.CENTER)
            add(JBScrollPane(evidenceArea).apply { preferredSize = java.awt.Dimension(0, 180) }, BorderLayout.SOUTH)
        }
        val graphTab = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8)
            val graphToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                isOpaque = false
                add(fitGraphButton)
                add(resetGraphButton)
                add(rerunLayoutButton)
                add(collapseAllGraphButton)
                add(kindFilterSelector)
                add(signalFilterSelector)
                add(platformFilterSelector)
                add(edgeTypeFilterSelector)
                add(architectureViewSelector)
                add(JBLabel("Depth"))
                add(depthSlider)
                add(fromSelectedCheck)
                add(pathHighlightModeCheck)
                add(JBLabel("Layout"))
                add(graphLayoutSelector)
                add(graphRendererStatusLabel)
            }
            val nodeDetailsActions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                isOpaque = false
                add(openNodeFileButton)
                add(focusNodeButton)
                add(showLayerPathButton)
            }
            val nodeDetailsPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1, 0, 0, 0)
                add(nodeDetailsTitle, BorderLayout.NORTH)
                add(JBScrollPane(nodeDetailsArea).apply { preferredSize = java.awt.Dimension(0, 145) }, BorderLayout.CENTER)
                add(nodeDetailsActions, BorderLayout.SOUTH)
            }
            val bottomPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(nodeDetailsPanel, BorderLayout.CENTER)
                add(
                    JPanel(BorderLayout()).apply {
                        isOpaque = false
                        border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 0, 1, 0, 0)
                        add(JBLabel("Hotspots").apply { border = JBUI.Borders.empty(0, 8) }, BorderLayout.NORTH)
                        add(JBScrollPane(hotspotsPanel).apply { preferredSize = java.awt.Dimension(290, 145) }, BorderLayout.CENTER)
                    },
                    BorderLayout.EAST,
                )
            }
            add(graphToolbar, BorderLayout.NORTH)
            add(graphContentPanel, BorderLayout.CENTER)
            add(bottomPanel, BorderLayout.SOUTH)
        }
        val tabs = javax.swing.JTabbedPane().apply {
            addTab("Overview", overviewTab)
            addTab("Evidence", evidenceTab)
            addTab("Graph", graphTab)
        }

        scanButton.addActionListener { triggerFastScan() }
        clearCacheButton.addActionListener { clearCache() }
        fitGraphButton.addActionListener { pushFiltersCommand(action = "FIT") }
        resetGraphButton.addActionListener { pushFiltersCommand(action = "RESET") }
        rerunLayoutButton.addActionListener { pushFiltersCommand(action = "RERUN_LAYOUT") }
        collapseAllGraphButton.addActionListener { pushClusterCommand(expand = false, clusterId = CLUSTER_ALL_ID) }
        val graphFilterChanged = {
            applyJvmFiltersAndRender()
        }
        kindFilterSelector.addActionListener { graphFilterChanged() }
        signalFilterSelector.addActionListener { graphFilterChanged() }
        platformFilterSelector.addActionListener { graphFilterChanged() }
        edgeTypeFilterSelector.addActionListener { graphFilterChanged() }
        architectureViewSelector.addActionListener { graphFilterChanged() }
        fromSelectedCheck.addActionListener { graphFilterChanged() }
        pathHighlightModeCheck.addActionListener { updateNodeDetailsPanel(selectedGraphNodeId) }
        depthSlider.addChangeListener(
            ChangeListener {
                if (!depthSlider.valueIsAdjusting) graphFilterChanged()
            },
        )
        openNodeFileButton.addActionListener { openSelectedGraphNodeFile() }
        focusNodeButton.addActionListener { focusOnSelectedNodeNeighborhood() }
        showLayerPathButton.addActionListener { showPathToLayerForSelectedNode() }
        graphLayoutSelector.addActionListener {
            val layout = graphLayoutSelector.selectedItem?.toString().orEmpty()
            lastLayout = layout.ifBlank { "cose" }
            pushFiltersCommand(layout = lastLayout)
        }
        actions.add(scanButton)
        actions.add(clearCacheButton)
        top.add(actions, BorderLayout.WEST)
        top.add(statusLabel, BorderLayout.EAST)

        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(top, BorderLayout.NORTH)
            add(header, BorderLayout.CENTER)
        }
        add(contentPanel, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        initializeGraphRenderer()
    }

    fun onTabOpened() {
        if (!autoTriggeredOnce) {
            autoTriggeredOnce = true
            triggerFastScan()
        }
    }

    fun triggerFastScan() {
        if (scanInProgress) return
        val basePath = project.basePath ?: run {
            overviewSummaryArea.text = "Project base path is not available."
            return
        }

        scanInProgress = true
        scanButton.isEnabled = false
        statusLabel.text = "Scanning..."
        bannerLabel.text = "⚡ Preliminary architecture detected... Deep analysis running."
        deepStatusLabel.text = "Starting fast scan..."

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Architecture Fast Scan", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Running high-signal architecture scan"
                    indicator.fraction = 0.1

                    val root = Path.of(basePath)
                    val highSignal = locator.locate(root)

                    indicator.fraction = 0.6
                    val signals = extractor.extract(root, highSignal)
                    val preliminary = scorer.score(root, highSignal, signals)

                    ApplicationManager.getApplication().invokeLater {
                        applyPreliminaryUi(preliminary)
                        deepStatusLabel.text = "Deep analysis running..."
                    }

                    val deepPipeline =
                        pipeline.run(
                            project = project,
                            root = root,
                            highSignal = highSignal,
                            fastSignals = signals,
                            preliminaryGuess = preliminary,
                            progress =
                                object : ArchitecturePipelineProgress {
                                    override fun checkCanceled() {
                                        indicator.checkCanceled()
                                    }

                                    override fun update(
                                        stage: ArchitecturePipelineStage,
                                        message: String,
                                        fraction: Double,
                                    ) {
                                        indicator.text = "Architecture ${stage.name.lowercase().replace('_', ' ')}"
                                        indicator.text2 = message
                                        indicator.fraction = fraction.coerceIn(0.0, 1.0)
                                        ApplicationManager.getApplication().invokeLater {
                                            deepStatusLabel.text = "${stage.name}: $message"
                                        }
                                    }
                                },
                        )
                    val deepGuess = scorer.score(root, highSignal, signals)

                    indicator.fraction = 1.0
                    val overviewText =
                        renderOverview(
                            preliminary = preliminary,
                            deepScan = deepPipeline.deepScan,
                            aggregateSummary = deepPipeline.aggregate,
                            aiSummary = deepPipeline.ai.summary,
                        )
                    val evidenceText =
                        renderEvidence(
                            highSignal = highSignal,
                            repoAggregate = deepPipeline.repoAggregate,
                            aiRawResponse = deepPipeline.ai.rawResponse,
                            aiSummary = deepPipeline.ai.summary,
                        )
                    val graphText =
                        renderGraph(
                            graphSummary = deepPipeline.graph,
                            repoAggregate = deepPipeline.repoAggregate,
                        )
                    val graphPayload =
                        graphPayloadBuilder.buildPayload(
                            root = root,
                            graphSummary = deepPipeline.graph,
                            repoAggregate = deepPipeline.repoAggregate,
                            fileKindSummary = deepPipeline.fileKinds,
                            analyzedFiles = deepPipeline.analyzedFiles,
                            summary = graphText,
                            aiHints = deepPipeline.ai.graphHints,
                        )
                    logger.info(overviewText)

                    ApplicationManager.getApplication().invokeLater {
                        val rootNormalized = normalizePath(root.toString())
                        latestFileAnalysisByRelative =
                            deepPipeline.analyzedFiles.associateBy { toRelativePath(it.path, rootNormalized) }
                        applyDeepUi(deepGuess, deepPipeline.ai.normalizedPatterns)
                        updateOverviewChips(
                            buildOverviewChips(
                                deepGuess = deepGuess,
                                aggregateSummary = deepPipeline.aggregate,
                                repoAggregate = deepPipeline.repoAggregate,
                            ),
                        )
                        updateEvidenceTable(
                            buildEvidenceRows(
                                aiPatterns = deepPipeline.ai.normalizedPatterns,
                                deepGuess = deepGuess,
                                repoAggregate = deepPipeline.repoAggregate,
                            ),
                        )
                        highLevelDiagramArea.text =
                            buildHighLevelDiagram(
                                patterns = deepPipeline.ai.normalizedPatterns.map { it.name }.ifEmpty {
                                    deepGuess.patternConfidences.map { it.pattern.name }
                                },
                                graphEdges = deepPipeline.repoAggregate.trimmedEdgeGraph,
                            )
                        overviewSummaryArea.text = overviewText
                        evidenceArea.text = evidenceText
                        updateGraphView(graphText, graphPayload)
                        highLevelDiagramArea.caretPosition = 0
                        overviewSummaryArea.caretPosition = 0
                        evidenceArea.caretPosition = 0
                        statusLabel.text = "Last scan complete."
                        scanButton.isEnabled = true
                        scanInProgress = false
                    }
                }

                override fun onThrowable(error: Throwable) {
                    logger.warn("Architecture fast scan failed", error)
                    ApplicationManager.getApplication().invokeLater {
                        val message = "Fast scan failed: ${error.message ?: "Unknown error"}"
                        updateEvidenceTable(emptyList())
                        overviewSummaryArea.text = message
                        highLevelDiagramArea.text = message
                        evidenceArea.text = message
                        updateGraphView(message, buildFallbackGraphPayloadModel(message))
                        statusLabel.text = "Scan failed."
                        deepStatusLabel.text = "Deep analysis failed."
                        scanButton.isEnabled = true
                        scanInProgress = false
                    }
                }

                override fun onCancel() {
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = "Scan cancelled."
                        deepStatusLabel.text = "Deep analysis cancelled."
                        scanButton.isEnabled = true
                        scanInProgress = false
                    }
                }
            },
        )
    }

    private fun applyPreliminaryUi(guess: PreliminaryArchitectureGuess) {
        bannerLabel.text = "⚡ Preliminary architecture detected... Deep analysis running."
        renderPatternRows(
            guess.patternConfidences.map {
                DisplayPattern(name = it.pattern.name, confidence = it.confidence)
            },
        )
        val top = guess.patternConfidences.firstOrNull()
        updateOverviewChips(
            listOf(
                "Top: ${top?.pattern?.name ?: "UNKNOWN"}",
                "Confidence: ${percent(top?.confidence ?: 0.0)}%",
                "Mode: fast",
            ),
        )
        highLevelDiagramArea.text =
            """
            [Scan Start]
                |
            [Signal Extraction]
                |
            [Preliminary Pattern: ${top?.pattern?.name ?: "UNKNOWN"}]
                |
            [Deep Analysis Running...]
            """.trimIndent()
        keySignalsArea.text =
            if (top == null) {
                "No preliminary signals detected."
            } else {
                buildString {
                    appendLine("Top preliminary pattern: ${top.pattern} (${percent(top.confidence)}%)")
                    appendLine()
                    top.signals.forEach { appendLine("- $it") }
                }
            }
        keySignalsArea.caretPosition = 0
    }

    private fun applyDeepUi(
        guess: PreliminaryArchitectureGuess,
        aiPatterns: List<com.example.open.reviewer.architecture.analysis.ArchitectureDetectedPattern>,
    ) {
        bannerLabel.text = "Deep architecture analysis complete."
        deepStatusLabel.text = "Deep scan finished. Results updated."
        val displayPatterns =
            if (aiPatterns.isNotEmpty()) {
                aiPatterns.map { DisplayPattern(name = it.name, confidence = it.confidence) }
            } else {
                guess.patternConfidences.map { DisplayPattern(name = it.pattern.name, confidence = it.confidence) }
            }
        renderPatternRows(displayPatterns)
        val top = displayPatterns.firstOrNull()
        if (top != null) {
            keySignalsArea.text =
                buildString {
                    appendLine("Top pattern after deep analysis: ${top.name} (${percent(top.confidence)}%)")
                    appendLine()
                    if (aiPatterns.isNotEmpty()) {
                        val evidence = aiPatterns.first().evidencePaths.take(5)
                        evidence.forEach { appendLine("- $it") }
                    } else {
                        guess.patternConfidences.firstOrNull()?.signals?.forEach { appendLine("- $it") }
                    }
                }
            keySignalsArea.caretPosition = 0
        }
    }

    private fun renderPatternRows(patterns: List<DisplayPattern>) {
        patternsPanel.removeAll()
        if (patterns.isEmpty()) {
            patternsPanel.add(JBLabel("No pattern guesses available.").apply { foreground = UIUtil.getContextHelpForeground() })
        } else {
            patterns.forEach { item ->
                patternsPanel.add(createPatternRow(item))
            }
        }
        patternsPanel.revalidate()
        patternsPanel.repaint()
    }

    private fun updateEvidenceTable(rows: List<EvidenceRow>) {
        evidenceTableModel.setRowCount(0)
        rows.forEach { row ->
            evidenceTableModel.addRow(arrayOf(row.pattern, row.confidence, row.signals, row.files))
        }
    }

    private fun updateOverviewChips(chips: List<String>) {
        chipsPanel.removeAll()
        chips.take(8).forEach { chip ->
            chipsPanel.add(
                JBLabel(chip).apply {
                    isOpaque = true
                    background = UIUtil.getPanelBackground().brighter()
                    border = JBUI.Borders.empty(4, 8)
                    font = JBFont.small()
                },
            )
        }
        chipsPanel.revalidate()
        chipsPanel.repaint()
    }

    private fun createPatternRow(item: DisplayPattern): JPanel {
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
        }
        val label =
            JBLabel("${item.name} (${percent(item.confidence)}%)").apply {
                font = JBFont.label().asBold()
            }
        val bar =
            JProgressBar(0, 100).apply {
                value = percent(item.confidence)
                isStringPainted = true
                string = "${value}%"
                foreground = progressColorFor(value)
            }
        row.add(label, BorderLayout.NORTH)
        row.add(bar, BorderLayout.CENTER)
        return row
    }

    private fun percent(value: Double): Int = (value * 100.0).toInt().coerceIn(0, 100)

    private fun progressColorFor(value: Int): Color {
        return when {
            value >= 70 -> Color(0x3BA55D)
            value >= 40 -> Color(0xD9A441)
            else -> Color(0xA8ADB5)
        }
    }

    private fun renderResult(
        highSignal: com.example.open.reviewer.architecture.scanner.HighSignalScanResult,
        signals: com.example.open.reviewer.architecture.scanner.FastSignalExtractionResult,
        preliminary: PreliminaryArchitectureGuess,
        deepScan: FullProjectScanResult,
        cacheResult: CacheAnalysisRunResult,
        analyzedFiles: List<com.example.open.reviewer.architecture.cache.CachedFileAnalysis>,
        gradleSummary: com.example.open.reviewer.architecture.analysis.GradleAnalysisResult,
        manifestSummary: com.example.open.reviewer.architecture.analysis.AndroidManifestAnalysisResult,
        kotlinPsiSummary: com.example.open.reviewer.architecture.analysis.KotlinPsiStageSummary,
        javaPsiSummary: com.example.open.reviewer.architecture.analysis.JavaPsiStageSummary,
        dartPsiSummary: com.example.open.reviewer.architecture.analysis.DartPsiStageSummary,
        fileKindSummary: com.example.open.reviewer.architecture.classifier.FileKindClassificationSummary,
        graphSummary: com.example.open.reviewer.architecture.analysis.ArchitectureGraphSnapshot,
        aggregateSummary: com.example.open.reviewer.architecture.analysis.ArchitectureAggregateSummary,
        repoAggregate: com.example.open.reviewer.architecture.analysis.RepoAggregatePayload,
        aiSummary: String,
        aiRawResponse: String?,
    ): String {
        return buildString {
            appendLine("Architecture Fast Scan")
            appendLine("======================")
            appendLine("Mode: ${signals.extractionMode}")
            appendLine("Confidence: ${signals.confidence}")
            appendLine("Elapsed: ${highSignal.elapsedMs} ms")
            appendLine("Scanned files: ${highSignal.scannedFileCount}")
            appendLine("Extractor scanned files: ${signals.scannedFiles}")
            appendLine("Unreadable files: ${signals.unreadableFiles}")
            appendLine()

            appendLine("Android Signals")
            appendLine("- ViewModel presence: ${signals.android.viewModelPresenceCount}")
            appendLine("- Hilt annotations: ${signals.android.hiltAnnotationCount}")
            appendLine("- Room annotations: ${signals.android.roomAnnotationCount}")
            appendLine("- Retrofit annotations: ${signals.android.retrofitAnnotationCount}")
            appendLine("- Compose usage: ${signals.android.composeUsageCount}")
            appendLine()

            appendLine("Flutter Signals")
            appendLine("- Provider/Riverpod/Bloc dependencies: ${signals.flutter.providerRiverpodBlocDependencyCount}")
            appendLine("- ChangeNotifier/Bloc/Cubit classes: ${signals.flutter.stateClassCount}")
            appendLine("- runApp root: ${signals.flutter.runAppRootCount}")
            appendLine("- GetIt usage: ${signals.flutter.getItUsageCount}")
            appendLine("- freezed/json_serializable: ${signals.flutter.freezedJsonSerializableCount}")
            appendLine()

            appendLine("Preliminary Architecture Guess")
            appendLine("- preliminary: ${preliminary.isPreliminary}")
            appendLine("- top pattern: ${preliminary.topPattern ?: "UNKNOWN"}")
            appendLine("- top confidence: ${"%.2f".format(preliminary.topConfidence)}")
            preliminary.patternConfidences.forEach { pattern ->
                appendLine("  * ${pattern.pattern}: conf=${"%.2f".format(pattern.confidence)} score=${pattern.score}/${pattern.maxScore}")
                pattern.signals.forEach { appendLine("    - $it") }
            }
            appendLine()

            appendLine("Deep Project Scan")
            appendLine("- elapsed: ${deepScan.elapsedMs} ms")
            appendLine("- scanned files: ${deepScan.scannedFileCount}")
            appendLine("- collected files: ${deepScan.collectedFileCount}")
            appendLine("- dart: ${deepScan.dartFiles.size}")
            appendLine("- kotlin: ${deepScan.kotlinFiles.size}")
            appendLine("- java: ${deepScan.javaFiles.size}")
            appendLine("- manifest: ${deepScan.manifestFiles.size}")
            appendLine("- gradle: ${deepScan.gradleFiles.size}")
            appendLine()

            appendLine("File Hash Cache")
            appendLine("- analyzed now: ${cacheResult.analyzedCount}")
            appendLine("- skipped unchanged: ${cacheResult.skippedCount}")
            appendLine("- unreadable: ${cacheResult.unreadableCount}")
            appendLine("- total cache size: ${fileCache.size()}")
            appendLine()

            appendLine("File Summaries (Top 30)")
            val topSummaries = analyzedFiles.sortedBy { it.path }.take(30)
            if (topSummaries.isEmpty()) {
                appendLine("- none (no changed files analyzed in this run)")
            } else {
                topSummaries.forEach { entry ->
                    val name = entry.path.substringAfterLast('/')
                    appendLine("- $name")
                    appendLine("  headline: ${entry.fileSummary.headline}")
                    entry.fileSummary.keyPoints.take(3).forEach { point ->
                        appendLine("  - $point")
                    }
                }
            }
            appendLine()

            appendLine("Gradle")
            appendLine("- analyzed files: ${gradleSummary.analyzedFiles}")
            appendLine("- unreadable files: ${gradleSummary.unreadableFiles}")
            appendLine("- plugins: ${gradleSummary.plugins.size}")
            appendLine("- dependencies: ${gradleSummary.dependencies.size}")
            appendLine("- modules: ${gradleSummary.modules.size}")
            appendLine("- android stack: ${gradleSummary.androidStackSignals.joinToString().ifBlank { "none" }}")
            appendLine("- has android stack: ${gradleSummary.hasAndroidStack()}")
            appendLine()

            appendLine("Android Manifest")
            appendLine("- analyzed files: ${manifestSummary.analyzedFiles}")
            appendLine("- unreadable files: ${manifestSummary.unreadableFiles}")
            appendLine("- components: ${manifestSummary.files.sumOf { it.components.size }}")
            appendLine("- permissions: ${manifestSummary.files.sumOf { it.permissions.size }}")
            appendLine("- launcher activities: ${manifestSummary.files.mapNotNull { it.launcherActivity }.distinct().joinToString().ifBlank { "none" }}")
            appendLine()

            appendLine("Kotlin PSI")
            appendLine("- scanned: ${kotlinPsiSummary.scannedFiles}")
            appendLine("- skipped large: ${kotlinPsiSummary.skippedLargeFiles}")
            appendLine("- unresolved: ${kotlinPsiSummary.unresolvedFiles}")
            appendLine("- android signals: ${kotlinPsiSummary.signals.joinToString().ifBlank { "none" }}")
            appendLine()

            appendLine("Java PSI")
            appendLine("- scanned: ${javaPsiSummary.scannedFiles}")
            appendLine("- skipped large: ${javaPsiSummary.skippedLargeFiles}")
            appendLine("- unresolved: ${javaPsiSummary.unresolvedFiles}")
            appendLine("- android signals: ${javaPsiSummary.signals.joinToString().ifBlank { "none" }}")
            appendLine()

            appendLine("Dart Extraction")
            appendLine("- scanned: ${dartPsiSummary.scannedFiles}")
            appendLine("- unresolved: ${dartPsiSummary.unresolvedFiles}")
            appendLine("- psi files: ${dartPsiSummary.psiFiles}")
            appendLine("- fallback files: ${dartPsiSummary.fallbackFiles}")
            appendLine("- extraction mode: ${dartPsiSummary.extractionMode}")
            appendLine("- confidence: ${dartPsiSummary.confidence}")
            appendLine("- has main(): ${dartPsiSummary.hasMainEntrypoint}")
            appendLine("- flutter signals: ${dartPsiSummary.signals.joinToString().ifBlank { "none" }}")
            appendLine()

            appendLine("File Kinds")
            appendLine("- total classified: ${fileKindSummary.classifications.size}")
            if (fileKindSummary.counts.isEmpty()) {
                appendLine("- none")
            } else {
                fileKindSummary.counts.forEach { (kind, count) ->
                    val sample = fileKindSummary.samples[kind].orEmpty().joinToString()
                    appendLine("- $kind: $count${if (sample.isBlank()) "" else " (e.g. $sample)"}")
                }
            }
            appendLine()

            appendLine("Graph")
            appendLine("- nodes: ${graphSummary.nodeCount}")
            appendLine("- edges: ${graphSummary.edgeCount}")
            appendLine("- top nodes: ${graphSummary.topNodes.joinToString().ifBlank { "none" }}")
            appendLine("- entrypoints: ${graphSummary.entrypoints.joinToString().ifBlank { "none" }}")
            appendLine("- trimmed edges: ${graphSummary.trimmedEdges.size}")
            appendLine()

            appendLine("Aggregate")
            appendLine("- files: ${aggregateSummary.fileCount}")
            appendLine("- lines: ${aggregateSummary.totalLines}")
            appendLine("- classes: ${aggregateSummary.totalClasses}")
            appendLine("- functions: ${aggregateSummary.totalFunctions}")
            appendLine("- top tags: ${aggregateSummary.topSignalTags.joinToString().ifBlank { "none" }}")
            appendLine()

            appendLine("Repo Aggregate Payload")
            appendLine("- bounded: ${repoAggregate.bounded}")
            appendLine("- budget: ${repoAggregate.tokenBudget}")
            appendLine("- estimated tokens: ${repoAggregate.estimatedTokens}")
            appendLine("- signal counts: ${repoAggregate.signalCounts.joinToString().ifBlank { "none" }}")
            appendLine("- representative files: ${repoAggregate.representativeFiles.joinToString().ifBlank { "none" }}")
            appendLine("- folder hints: ${repoAggregate.folderStructureHints.joinToString().ifBlank { "none" }}")
            appendLine("- trimmed edges: ${repoAggregate.trimmedEdgeGraph.joinToString().ifBlank { "none" }}")
            appendLine()

            appendLine("AI Call")
            appendLine("- $aiSummary")
            appendLine("- raw response:")
            appendLine(aiRawResponse?.ifBlank { "<empty>" } ?: "<none>")
            appendLine()

            appendSection("AndroidManifest", highSignal.androidManifestFiles)
            appendSection("Gradle Build Files", highSignal.gradleBuildFiles)
            appendSection("Settings Gradle Files", highSignal.settingsGradleFiles)
            appendSection("Application Files", highSignal.applicationFiles)
            appendSection("Top ViewModels", highSignal.topViewModelFiles)
            appendSection("Top DI Modules", highSignal.topDiModuleFiles)
            appendSection("Flutter main.dart", highSignal.flutterMainFiles)
            appendSection("pubspec.yaml", highSignal.pubspecFiles)
            appendSection("Top Flutter Feature Folders", highSignal.topFlutterFeatureFolders)
            appendSection("Top Provider/Bloc/Notifier Files", highSignal.topFlutterStateFiles)
        }
    }

    private fun renderOverview(
        preliminary: PreliminaryArchitectureGuess,
        deepScan: FullProjectScanResult,
        aggregateSummary: com.example.open.reviewer.architecture.analysis.ArchitectureAggregateSummary,
        aiSummary: String,
    ): String {
        return buildString {
            appendLine("Overview")
            appendLine("--------")
            appendLine("Top preliminary pattern: ${preliminary.topPattern ?: "UNKNOWN"} (${percent(preliminary.topConfidence)}%)")
            appendLine("Scanned files: ${deepScan.scannedFileCount}")
            appendLine("Collected files: ${deepScan.collectedFileCount}")
            appendLine("Files analyzed: ${aggregateSummary.fileCount}")
            appendLine("Total lines/classes/functions: ${aggregateSummary.totalLines}/${aggregateSummary.totalClasses}/${aggregateSummary.totalFunctions}")
            appendLine("Top tags: ${aggregateSummary.topSignalTags.joinToString().ifBlank { "none" }}")
            appendLine()
            appendLine("AI")
            appendLine(aiSummary)
        }
    }

    private fun renderEvidence(
        highSignal: com.example.open.reviewer.architecture.scanner.HighSignalScanResult,
        repoAggregate: com.example.open.reviewer.architecture.analysis.RepoAggregatePayload,
        aiRawResponse: String?,
        aiSummary: String,
    ): String {
        return buildString {
            appendLine("Evidence")
            appendLine("--------")
            appendLine("Signal counts: ${repoAggregate.signalCounts.joinToString().ifBlank { "none" }}")
            appendLine("Representative files:")
            repoAggregate.representativeFiles.forEach { appendLine("- $it") }
            appendLine()
            appendLine("High-signal files")
            appendSection("AndroidManifest", highSignal.androidManifestFiles)
            appendSection("Gradle Build Files", highSignal.gradleBuildFiles)
            appendSection("Application Files", highSignal.applicationFiles)
            appendSection("Top ViewModels", highSignal.topViewModelFiles)
            appendSection("Top DI Modules", highSignal.topDiModuleFiles)
            appendLine("AI summary: $aiSummary")
            appendLine("AI raw response:")
            appendLine(aiRawResponse?.ifBlank { "<empty>" } ?: "<none>")
        }
    }

    private fun renderGraph(
        graphSummary: com.example.open.reviewer.architecture.analysis.ArchitectureGraphSnapshot,
        repoAggregate: com.example.open.reviewer.architecture.analysis.RepoAggregatePayload,
    ): String {
        return buildString {
            appendLine("Graph")
            appendLine("-----")
            appendLine("Nodes: ${graphSummary.nodeCount}")
            appendLine("Edges: ${graphSummary.edgeCount}")
            appendLine("Top nodes: ${graphSummary.topNodes.joinToString().ifBlank { "none" }}")
            appendLine("Entrypoints: ${graphSummary.entrypoints.joinToString().ifBlank { "none" }}")
            appendLine()
            appendLine("Trimmed edges:")
            if (repoAggregate.trimmedEdgeGraph.isEmpty()) {
                appendLine("- none")
            } else {
                repoAggregate.trimmedEdgeGraph.forEach { appendLine("- $it") }
            }
        }
    }

    private fun buildOverviewChips(
        deepGuess: PreliminaryArchitectureGuess,
        aggregateSummary: com.example.open.reviewer.architecture.analysis.ArchitectureAggregateSummary,
        repoAggregate: com.example.open.reviewer.architecture.analysis.RepoAggregatePayload,
    ): List<String> {
        val top = deepGuess.patternConfidences.firstOrNull()
        val topSignals = repoAggregate.signalCounts.take(2)
        return buildList {
            add("Pattern: ${top?.pattern?.name ?: "UNKNOWN"}")
            add("Confidence: ${percent(top?.confidence ?: 0.0)}%")
            add("Files: ${aggregateSummary.fileCount}")
            add("Edges: ${repoAggregate.trimmedEdgeGraph.size}")
            topSignals.forEach { add(it) }
        }
    }

    private fun buildHighLevelDiagram(
        patterns: List<String>,
        graphEdges: List<String>,
    ): String {
        val patternText = patterns.take(3).joinToString(", ").ifBlank { "Unknown" }
        val uiToVm = graphEdges.count { it.contains("ui", ignoreCase = true) && it.contains("viewmodel", ignoreCase = true) }
        val vmToRepo =
            graphEdges.count {
                it.contains("viewmodel", ignoreCase = true) &&
                    (it.contains("repo", ignoreCase = true) || it.contains("repository", ignoreCase = true))
            }
        return """
        Architecture Diagram
        --------------------
        Pattern(s): $patternText

        [UI Layer]  --($uiToVm)-->  [ViewModel Layer]  --($vmToRepo)-->  [Data/Repository Layer]
             |                                                              |
             +------------------ user flows / entrypoints ------------------+
        """.trimIndent()
    }

    private fun buildEvidenceRows(
        aiPatterns: List<com.example.open.reviewer.architecture.analysis.ArchitectureDetectedPattern>,
        deepGuess: PreliminaryArchitectureGuess,
        repoAggregate: com.example.open.reviewer.architecture.analysis.RepoAggregatePayload,
    ): List<EvidenceRow> {
        if (aiPatterns.isNotEmpty()) {
            return aiPatterns.take(10).map { pattern ->
                EvidenceRow(
                    pattern = pattern.name,
                    confidence = "${percent(pattern.confidence)}%",
                    signals = inferSignalsForPattern(pattern.name, repoAggregate.signalCounts),
                    files = pattern.evidencePaths.take(4).joinToString(", ").ifBlank { "none" },
                )
            }
        }
        return deepGuess.patternConfidences.take(5).map { pattern ->
            EvidenceRow(
                pattern = pattern.pattern.name,
                confidence = "${percent(pattern.confidence)}%",
                signals = pattern.signals.take(3).joinToString(" | ").ifBlank { "none" },
                files = repoAggregate.representativeFiles.take(2).joinToString(", ").ifBlank { "none" },
            )
        }
    }

    private fun inferSignalsForPattern(
        patternName: String,
        signalCounts: List<String>,
    ): String {
        val tags =
            signalCounts.mapNotNull {
                val idx = it.indexOf(':')
                if (idx <= 0) null else it.substring(0, idx)
            }
        val lower = patternName.lowercase()
        val preferred =
            when {
                "mvvm" in lower -> listOf("viewmodel", "repository", "ui")
                "clean" in lower -> listOf("repository", "domain", "usecase", "viewmodel")
                "riverpod" in lower -> listOf("state-management", "ui")
                "bloc" in lower -> listOf("state-management", "ui")
                "provider" in lower -> listOf("state-management", "ui")
                else -> emptyList()
            }
        val matched = preferred.filter { p -> tags.any { it.contains(p, ignoreCase = true) } }
        val fallback = tags.take(3)
        return (matched + fallback).distinct().take(3).joinToString(" | ").ifBlank { "none" }
    }

    private fun StringBuilder.appendSection(
        title: String,
        values: List<String>,
    ) {
        appendLine(title)
        if (values.isEmpty()) {
            appendLine("- none")
            appendLine()
            return
        }
        values.forEach { appendLine("- $it") }
        appendLine()
    }

    private fun initializeGraphRenderer() {
        graphContentPanel.add(graphFallbackPanel, GRAPH_CARD_FALLBACK)
        if (!JBCefApp.isSupported()) {
            setGraphRendererFallback("JCEF unavailable")
            return
        }

        val browser = JBCefBrowser()
        graphBrowser = browser
        graphJsQuery = JBCefJSQuery.create(browser)
        graphJsQuery?.addHandler { request ->
            handleGraphEventFromJs(request)
            null
        }
        graphRendererReady = false
        queuedGraphCommands.clear()
        graphContentPanel.add(browser.component, GRAPH_CARD_JCEF)
        graphCardLayout.show(graphContentPanel, GRAPH_CARD_JCEF)
        graphRendererStatusLabel.text = "Renderer: JCEF OK"
        setGraphControlsEnabled(true)
        Disposer.register(project) { browser.dispose() }
        graphJsQuery?.let { query -> Disposer.register(project, query) }
        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    httpStatusCode: Int,
                ) {
                    if (frame == null || !frame.isMain) return
                    bindGraphEventBridge()
                }
            },
            browser.cefBrowser,
        )
        loadGraphShell(browser)
    }

    private fun loadGraphShell(browser: JBCefBrowser) {
        val indexHtml = readResourceText("/graph/index.html")
        if (indexHtml == null) {
            setGraphRendererFallback("Resource missing")
            updateGraphView("Missing graph renderer resource: /graph/index.html")
            return
        }
        val cytoscapeJs = readResourceText("/graph/vendor/cytoscape.min.js")
        if (cytoscapeJs == null) {
            logger.warn("Cytoscape bundle missing at /graph/vendor/cytoscape.min.js; graph will run in fallback visual mode.")
        }
        val graphJs = readResourceText("/graph/graph.js")
        if (graphJs == null) {
            setGraphRendererFallback("Resource missing")
            updateGraphView("Missing graph renderer resource: /graph/graph.js")
            return
        }
        val mergedHtml =
            indexHtml.replace(
                "<script src=\"./graph.js\"></script>",
                buildString {
                    if (!cytoscapeJs.isNullOrBlank()) {
                        append("<script>\n")
                        append(cytoscapeJs)
                        append("\n</script>\n")
                    }
                    append("<script>\n")
                    append(graphJs)
                    append("\n</script>")
                },
            )
        runCatching { browser.loadHTML(mergedHtml) }
            .onFailure { error ->
                setGraphRendererFallback("JCEF load failed")
                updateGraphView("Graph renderer failed to load: ${error.message ?: "Unknown error"}")
            }
    }

    private fun readResourceText(path: String): String? {
        val stream = javaClass.getResourceAsStream(path) ?: return null
        return stream.use { input ->
            String(input.readAllBytes(), StandardCharsets.UTF_8)
        }
    }

    private fun setGraphRendererFallback(reason: String) {
        graphBrowser?.dispose()
        graphBrowser = null
        graphJsQuery = null
        graphRendererReady = false
        queuedGraphCommands.clear()
        graphCardLayout.show(graphContentPanel, GRAPH_CARD_FALLBACK)
        graphRendererStatusLabel.text = "Renderer: fallback ($reason)"
        setGraphControlsEnabled(false)
    }

    private fun setGraphControlsEnabled(enabled: Boolean) {
        fitGraphButton.isEnabled = enabled
        resetGraphButton.isEnabled = enabled
        rerunLayoutButton.isEnabled = enabled
        collapseAllGraphButton.isEnabled = enabled
        kindFilterSelector.isEnabled = enabled
        signalFilterSelector.isEnabled = enabled
        platformFilterSelector.isEnabled = enabled
        edgeTypeFilterSelector.isEnabled = enabled
        architectureViewSelector.isEnabled = enabled
        depthSlider.isEnabled = enabled
        fromSelectedCheck.isEnabled = enabled
        pathHighlightModeCheck.isEnabled = enabled
        graphLayoutSelector.isEnabled = enabled
        openNodeFileButton.isEnabled = enabled && selectedGraphNodeId != null
        focusNodeButton.isEnabled = enabled && selectedGraphNodeId != null
        showLayerPathButton.isEnabled = enabled && selectedGraphNodeId != null
    }

    private fun bindGraphEventBridge() {
        val query = graphJsQuery ?: return
        val browser = graphBrowser ?: return
        val cefBrowser = browser.cefBrowser
        val url = cefBrowser.url.ifBlank { "about:blank" }
        val script =
            """
            window.__openReviewerSendToJvm = function(message) {
              ${query.inject("message")}
            };
            if (window.openReviewerBridge && typeof window.openReviewerBridge.onJvmBridgeReady === "function") {
              window.openReviewerBridge.onJvmBridgeReady();
            }
            """.trimIndent()
        cefBrowser.executeJavaScript(script, url, 0)
    }

    private fun sendGraphBridgeCommand(
        type: GraphBridgeCommandType,
        payloadJson: String = "{}",
    ) {
        if (graphBrowser == null) return
        val message = """{"type":"${type.name}","payload":$payloadJson}"""
        if (!graphRendererReady) {
            queuedGraphCommands.addLast(message)
            return
        }
        executeBridgeMessage(message)
    }

    private fun executeBridgeMessage(messageJson: String) {
        val browser = graphBrowser ?: return
        val cefBrowser = browser.cefBrowser
        val url = cefBrowser.url.ifBlank { "about:blank" }
        val script = "window.openReviewerBridge?.receiveCommand(${toJsString(messageJson)});"
        cefBrowser.executeJavaScript(script, url, 0)
    }

    private fun flushQueuedGraphCommands() {
        if (!graphRendererReady) return
        while (queuedGraphCommands.isNotEmpty()) {
            executeBridgeMessage(queuedGraphCommands.removeFirst())
        }
    }

    private fun pushFiltersCommand(
        layout: String? = null,
        action: String? = null,
    ) {
        val payload =
            buildString {
                append('{')
                var first = true
                if (!layout.isNullOrBlank()) {
                    append("\"layout\":${toJsString(layout)}")
                    first = false
                }
                if (!action.isNullOrBlank()) {
                    if (!first) append(',')
                    append("\"action\":${toJsString(action)}")
                }
                append('}')
            }
        sendGraphBridgeCommand(GraphBridgeCommandType.APPLY_FILTERS, payload)
    }

    private fun pushFocusNode(nodeId: String) {
        sendGraphBridgeCommand(
            GraphBridgeCommandType.FOCUS_NODE,
            """{"nodeId":${toJsString(nodeId)}}""",
        )
    }

    private fun pushHighlightPayload(
        nodeIds: List<String>,
        edgeIds: List<String> = emptyList(),
    ) {
        val nodeIdJson = nodeIds.joinToString(prefix = "[", postfix = "]") { toJsString(it) }
        val edgeIdJson = edgeIds.joinToString(prefix = "[", postfix = "]") { toJsString(it) }
        sendGraphBridgeCommand(
            GraphBridgeCommandType.SET_HIGHLIGHT,
            """{"nodeIds":$nodeIdJson,"edgeIds":$edgeIdJson}""",
        )
    }

    private fun pushClusterCommand(
        expand: Boolean,
        clusterId: String,
    ) {
        val type = if (expand) GraphBridgeCommandType.EXPAND_CLUSTER else GraphBridgeCommandType.COLLAPSE_CLUSTER
        sendGraphBridgeCommand(type, """{"clusterId":${toJsString(clusterId)}}""")
    }

    private fun pushGraphProtocol(
        summary: String,
        payload: com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload,
    ) {
        pushGraphProtocolJson(summary, graphPayloadBuilder.toJson(payload))
    }

    private fun pushGraphProtocolJson(
        summary: String,
        payloadJson: String,
    ) {
        sendGraphBridgeCommand(GraphBridgeCommandType.SET_GRAPH, payloadJson)
        sendGraphBridgeCommand(
            GraphBridgeCommandType.SET_THEME,
            """{"mode":${toJsString(if (UIUtil.isUnderDarcula()) "dark" else "light")}}""",
        )
        pushFiltersCommand(layout = lastLayout)
    }

    private fun buildFallbackGraphPayloadModel(summary: String): com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload {
        val edges = parseEdgeLines(summary)
        val nodeIds = linkedSetOf<String>()
        edges.forEach { edge ->
            nodeIds += edge.first
            nodeIds += edge.second
        }
        return com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload(
            version = 1,
            bounded = true,
            nodeLimit = 0,
            edgeLimit = 0,
            droppedNodeCount = 0,
            droppedEdgeCount = 0,
            nodes =
                nodeIds.map { id ->
                    com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode(
                        id = id,
                        label = id.substringAfterLast('/'),
                        path = id,
                        kind = inferKindForFallback(id),
                        platform = inferPlatformForFallback(id),
                        signals = emptyList(),
                        group = "fallback",
                        metrics = com.example.open.reviewer.architecture.analysis.CytoscapeGraphNodeMetrics(0, 0),
                        hotspotScore = 0,
                    )
                },
            edges =
                edges.mapIndexed { index, edge ->
                    com.example.open.reviewer.architecture.analysis.CytoscapeGraphEdge(
                        id = "e$index",
                        source = edge.first,
                        target = edge.second,
                        edgeType = "IMPORT",
                        weight = 1,
                        count = 1,
                    )
                },
            summary = summary,
        )
    }

    private fun parseEdgeLines(summary: String): List<Pair<String, String>> {
        return summary
            .lineSequence()
            .map { it.trim().removePrefix("- ").trim() }
            .mapNotNull { line ->
                val split = line.split("->").map { it.trim() }
                if (split.size == 2 && split[0].isNotBlank() && split[1].isNotBlank()) {
                    split[0] to split[1]
                } else {
                    null
                }
            }.toList()
    }

    private fun handleGraphEventFromJs(rawMessage: String?) {
        val message = rawMessage.orEmpty().trim()
        if (message.isBlank()) {
            logger.warn("Graph bridge received empty JS event.")
            return
        }
        val type = extractJsonString(message, "type")
        if (type.isNullOrBlank()) {
            logger.warn("Graph bridge received malformed event: $message")
            return
        }
        when (type) {
            GraphBridgeEventType.READY.name -> {
                graphRendererReady = true
                flushQueuedGraphCommands()
                pushGraphProtocolJson(lastGraphSummaryText, lastGraphPayloadJson)
            }
            GraphBridgeEventType.NODE_CLICK.name,
            -> {
                selectedGraphNodeId = extractJsonString(message, "nodeId")
                ApplicationManager.getApplication().invokeLater {
                    if (fromSelectedCheck.isSelected) applyJvmFiltersAndRender()
                    updateNodeDetailsPanel(selectedGraphNodeId)
                }
                logger.info("Graph event [$type]: $message")
            }
            GraphBridgeEventType.NODE_DOUBLE_CLICK.name -> {
                selectedGraphNodeId = extractJsonString(message, "nodeId")
                ApplicationManager.getApplication().invokeLater {
                    updateNodeDetailsPanel(selectedGraphNodeId)
                    openSelectedGraphNodeFile()
                }
                logger.info("Graph event [$type]: $message")
            }
            GraphBridgeEventType.SELECTION_CHANGED.name -> {
                selectedGraphNodeId = extractJsonString(message, "nodeId") ?: selectedGraphNodeId
                ApplicationManager.getApplication().invokeLater {
                    if (fromSelectedCheck.isSelected) applyJvmFiltersAndRender()
                    updateNodeDetailsPanel(selectedGraphNodeId)
                }
                logger.info("Graph event [$type]: $message")
            }
            GraphBridgeEventType.VIEWPORT_CHANGED.name -> logger.info("Graph event [$type]: $message")
            else -> logger.warn("Graph bridge received unknown event type [$type]: $message")
        }
    }

    private fun updateGraphView(
        text: String,
        payload: com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload? = null,
    ) {
        lastGraphSummaryText = text
        lastBaseGraphPayload = payload ?: buildFallbackGraphPayloadModel(text)
        applyJvmFiltersAndRender()
    }

    private fun applyJvmFiltersAndRender() {
        val base = lastBaseGraphPayload ?: return
        val startedAt = System.nanoTime()
        val filtered = filterGraphPayload(base)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val rendered =
            if (filtered.nodes.size > FILTER_REFINE_NODE_LIMIT) {
                buildFallbackGraphPayloadModel(
                    "Refine filters: ${filtered.nodes.size} nodes selected. " +
                        "Use kind/signal/platform/edge/depth filters to narrow the graph.",
                )
            } else {
                filtered.copy(
                    summary =
                        "${base.summary}\n\n" +
                            "[JVM filters] nodes=${filtered.nodes.size} edges=${filtered.edges.size} " +
                            "depth=${depthSlider.value} time=${elapsedMs}ms",
                )
            }
        val architectureView = buildArchitecturePatternView(rendered)
        val finalRendered =
            architectureView.copy(
                summary =
                    architectureView.summary +
                        "\n\n[Architecture view] ${currentArchitectureViewMode()}",
            )
        lastRenderedGraphPayload = finalRendered
        if (selectedGraphNodeId != null && finalRendered.nodes.none { it.id == selectedGraphNodeId }) {
            selectedGraphNodeId = null
        }
        lastGraphPayloadJson = graphPayloadBuilder.toJson(finalRendered)
        graphArea.text = finalRendered.summary
        graphArea.caretPosition = 0
        updateHotspotsPanel(finalRendered)
        updateNodeDetailsPanel(selectedGraphNodeId)
        pushGraphProtocol(lastGraphSummaryText, finalRendered)
    }

    private fun currentArchitectureViewMode(): String {
        return architectureViewSelector.selectedItem
            ?.toString()
            .orEmpty()
            .removePrefix("View:")
            .trim()
            .ifBlank { "Layered" }
    }

    private fun buildArchitecturePatternView(
        base: com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload,
    ): com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload {
        if (base.nodes.isEmpty() || base.edges.isEmpty()) return base
        return when (currentArchitectureViewMode()) {
            "Layered" -> aggregateArchitectureView(base, ::layeredRoleFor)
            "MVVM" -> aggregateArchitectureView(base, ::mvvmRoleFor)
            "Clean" -> aggregateArchitectureView(base, ::cleanRoleFor)
            "State Mgmt" -> aggregateArchitectureView(base, ::stateMgmtRoleFor)
            "Feature Topology" -> buildFeatureTopologyView(base)
            else -> aggregateArchitectureView(base, ::layeredRoleFor)
        }
    }

    private fun aggregateArchitectureView(
        base: com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload,
        roleOf: (com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode) -> String,
    ): com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload {
        val mode = currentArchitectureViewMode()
        val rolesByNodeId = base.nodes.associate { it.id to roleOf(it) }
        val membersByRole = linkedMapOf<String, MutableList<com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode>>()
        base.nodes.forEach { node ->
            val role = rolesByNodeId[node.id] ?: ROLE_OTHER
            membersByRole.getOrPut(role) { mutableListOf() }.add(node)
        }

        val roleOrder =
            when (mode) {
                "MVVM" -> listOf("UI", "VIEWMODEL", "REPOSITORY", "DATA", "DI", "CONFIG", "TEST", ROLE_OTHER)
                "Clean" -> listOf("UI", "USE_CASE", "DOMAIN", "REPOSITORY", "DATA_SOURCE", "DI", "CONFIG", "TEST", ROLE_OTHER)
                "State Mgmt" -> listOf("UI", "STATE_MGMT", "REPOSITORY", "DATA", "DI", "CONFIG", "TEST", ROLE_OTHER)
                else -> listOf("UI", "STATE", "DOMAIN", "DATA", "DI", "CONFIG", "TEST", ROLE_OTHER)
            }

        val selectedRoles = roleOrder.filter { role -> !membersByRole[role].isNullOrEmpty() }
        val roleIdMap = selectedRoles.associateWith { role -> "arch:${mode.lowercase().replace(' ', '_')}:$role" }

        val roleNodes =
            selectedRoles.map { role ->
                val members = membersByRole[role].orEmpty()
                val fanIn = members.sumOf { it.metrics.fanIn }
                val fanOut = members.sumOf { it.metrics.fanOut }
                val signals =
                    members
                        .asSequence()
                        .flatMap { it.signals.asSequence() }
                        .distinct()
                        .sorted()
                        .take(8)
                        .toList()
                com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode(
                    id = roleIdMap.getValue(role),
                    label = "$role (${members.size})",
                    path = "__arch__/$mode/$role",
                    kind = roleKind(role),
                    platform = "mixed",
                    signals = signals,
                    group = "lane:${role.lowercase()}",
                    metrics =
                        com.example.open.reviewer.architecture.analysis.CytoscapeGraphNodeMetrics(
                            fanIn = fanIn,
                            fanOut = fanOut,
                        ),
                    hotspotScore = fanIn + fanOut,
                )
            }

        data class AggregatedEdge(
            val sourceRole: String,
            val targetRole: String,
            var count: Int = 0,
            var weight: Int = 0,
            var violations: Int = 0,
        )

        val aggregated = linkedMapOf<String, AggregatedEdge>()
        base.edges.forEach { edge ->
            val sourceRole = rolesByNodeId[edge.source] ?: return@forEach
            val targetRole = rolesByNodeId[edge.target] ?: return@forEach
            if (sourceRole == targetRole) return@forEach
            val key = "$sourceRole->$targetRole"
            val item = aggregated.getOrPut(key) { AggregatedEdge(sourceRole = sourceRole, targetRole = targetRole) }
            item.count += 1
            item.weight += edge.weight
            if (isViolationForMode(mode, sourceRole, targetRole)) {
                item.violations += 1
            }
        }

        val roleEdges =
            aggregated.values
                .sortedWith(compareByDescending<AggregatedEdge> { it.count }.thenBy { it.sourceRole }.thenBy { it.targetRole })
                .mapIndexedNotNull { index, item ->
                    val sourceId = roleIdMap[item.sourceRole] ?: return@mapIndexedNotNull null
                    val targetId = roleIdMap[item.targetRole] ?: return@mapIndexedNotNull null
                    com.example.open.reviewer.architecture.analysis.CytoscapeGraphEdge(
                        id = "arch-e$index",
                        source = sourceId,
                        target = targetId,
                        edgeType = if (item.violations > 0) "VIOLATION" else "USES_TYPE",
                        weight = item.weight.coerceAtLeast(item.count),
                        count = item.count,
                    )
                }

        val violationCount = aggregated.values.sumOf { it.violations }
        val summaries =
            buildString {
                append(base.summary)
                appendLine()
                appendLine()
                appendLine("[Pattern mode: $mode]")
                appendLine("- roles: ${roleNodes.size}")
                appendLine("- flow edges: ${roleEdges.size}")
                appendLine("- rule violations: $violationCount")
            }.trim()

        return base.copy(
            nodes = roleNodes,
            edges = roleEdges,
            droppedNodeCount = (base.nodes.size - roleNodes.size).coerceAtLeast(0),
            droppedEdgeCount = (base.edges.size - roleEdges.size).coerceAtLeast(0),
            summary = summaries,
            bounded = true,
        )
    }

    private fun buildFeatureTopologyView(
        base: com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload,
    ): com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload {
        val nodesById = base.nodes.associateBy { it.id }
        val groupByNodeId =
            base.nodes.associate { node ->
                val group = node.group.ifBlank { "folder:root" }
                node.id to group
            }
        val membersByGroup = linkedMapOf<String, MutableList<com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode>>()
        base.nodes.forEach { node ->
            val group = groupByNodeId[node.id] ?: "folder:root"
            membersByGroup.getOrPut(group) { mutableListOf() }.add(node)
        }

        val groupsToKeep =
            membersByGroup.entries
                .sortedWith(compareByDescending<Map.Entry<String, MutableList<com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode>>> { it.value.size }.thenBy { it.key })
                .take(MAX_FEATURE_GROUPS)
                .map { it.key }
                .toSet()

        val nodeGroupIdBySource =
            groupByNodeId.mapValues { (_, group) ->
                if (group in groupsToKeep) {
                    "feature:$group"
                } else {
                    "feature:other"
                }
            }

        val topologyGroups = (groupsToKeep.map { "feature:$it" } + "feature:other").distinct()
        val topologyNodes =
            topologyGroups.mapNotNull { groupId ->
                val members =
                    nodeGroupIdBySource.entries
                        .asSequence()
                        .filter { it.value == groupId }
                        .mapNotNull { entry -> nodesById[entry.key] }
                        .toList()
                if (members.isEmpty()) return@mapNotNull null
                val labelBase =
                    groupId
                        .removePrefix("feature:")
                        .removePrefix("folder:")
                        .removePrefix("pkg:")
                        .removePrefix("module:")
                        .ifBlank { "other" }
                val fanIn = members.sumOf { it.metrics.fanIn }
                val fanOut = members.sumOf { it.metrics.fanOut }
                com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode(
                    id = groupId,
                    label = "$labelBase (${members.size})",
                    path = "__arch__/feature/$labelBase",
                    kind = "DOMAIN",
                    platform = "mixed",
                    signals =
                        members
                            .asSequence()
                            .flatMap { it.signals.asSequence() }
                            .distinct()
                            .sorted()
                            .take(8)
                            .toList(),
                    group = "feature-topology",
                    metrics =
                        com.example.open.reviewer.architecture.analysis.CytoscapeGraphNodeMetrics(
                            fanIn = fanIn,
                            fanOut = fanOut,
                        ),
                    hotspotScore = fanIn + fanOut,
                )
            }

        data class GroupEdge(
            val source: String,
            val target: String,
            var count: Int = 0,
            var weight: Int = 0,
        )
        val groupedEdges = linkedMapOf<String, GroupEdge>()
        base.edges.forEach { edge ->
            val sourceGroup = nodeGroupIdBySource[edge.source] ?: return@forEach
            val targetGroup = nodeGroupIdBySource[edge.target] ?: return@forEach
            if (sourceGroup == targetGroup) return@forEach
            val key = "$sourceGroup->$targetGroup"
            val item = groupedEdges.getOrPut(key) { GroupEdge(source = sourceGroup, target = targetGroup) }
            item.count += 1
            item.weight += edge.weight
        }
        val topologyEdges =
            groupedEdges.values
                .sortedWith(compareByDescending<GroupEdge> { it.count }.thenBy { it.source }.thenBy { it.target })
                .mapIndexed { index, edge ->
                    com.example.open.reviewer.architecture.analysis.CytoscapeGraphEdge(
                        id = "feature-e$index",
                        source = edge.source,
                        target = edge.target,
                        edgeType = "USES_TYPE",
                        weight = edge.weight.coerceAtLeast(edge.count),
                        count = edge.count,
                    )
                }

        val summary =
            buildString {
                append(base.summary)
                appendLine()
                appendLine()
                appendLine("[Pattern mode: Feature Topology]")
                appendLine("- groups: ${topologyNodes.size}")
                appendLine("- inter-group dependencies: ${topologyEdges.size}")
            }.trim()

        return base.copy(
            nodes = topologyNodes,
            edges = topologyEdges,
            droppedNodeCount = (base.nodes.size - topologyNodes.size).coerceAtLeast(0),
            droppedEdgeCount = (base.edges.size - topologyEdges.size).coerceAtLeast(0),
            summary = summary,
            bounded = true,
        )
    }

    private fun layeredRoleFor(node: com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode): String {
        return when (node.kind.uppercase()) {
            "UI" -> "UI"
            "STATE" -> "STATE"
            "MODEL", "DOMAIN" -> "DOMAIN"
            "REPO", "DATA" -> "DATA"
            "DI" -> "DI"
            "CONFIG" -> "CONFIG"
            "TEST" -> "TEST"
            else -> ROLE_OTHER
        }
    }

    private fun mvvmRoleFor(node: com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode): String {
        val lower = "${node.label} ${node.path}".lowercase()
        return when {
            node.kind.equals("UI", ignoreCase = true) -> "UI"
            "viewmodel" in lower || "bloc" in lower || "provider" in lower || "riverpod" in lower || node.kind.equals("STATE", ignoreCase = true) -> "VIEWMODEL"
            node.kind.equals("REPO", ignoreCase = true) || "repository" in lower -> "REPOSITORY"
            "datasource" in lower || "remote" in lower || "dao" in lower || "api" in lower || node.kind.equals("DATA", ignoreCase = true) -> "DATA"
            node.kind.equals("DI", ignoreCase = true) || "hilt" in lower || "/di/" in lower -> "DI"
            node.kind.equals("CONFIG", ignoreCase = true) -> "CONFIG"
            node.kind.equals("TEST", ignoreCase = true) -> "TEST"
            else -> ROLE_OTHER
        }
    }

    private fun cleanRoleFor(node: com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode): String {
        val lower = "${node.label} ${node.path}".lowercase()
        return when {
            node.kind.equals("UI", ignoreCase = true) -> "UI"
            "usecase" in lower || "interactor" in lower -> "USE_CASE"
            node.kind.equals("MODEL", ignoreCase = true) || node.kind.equals("DOMAIN", ignoreCase = true) || "/domain/" in lower -> "DOMAIN"
            node.kind.equals("REPO", ignoreCase = true) || "repository" in lower -> "REPOSITORY"
            "datasource" in lower || "api" in lower || "dao" in lower || "remote" in lower || "local" in lower -> "DATA_SOURCE"
            node.kind.equals("DI", ignoreCase = true) || "hilt" in lower || "/di/" in lower -> "DI"
            node.kind.equals("CONFIG", ignoreCase = true) -> "CONFIG"
            node.kind.equals("TEST", ignoreCase = true) -> "TEST"
            else -> ROLE_OTHER
        }
    }

    private fun stateMgmtRoleFor(node: com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode): String {
        val lower = "${node.label} ${node.path} ${node.signals.joinToString(" ")}".lowercase()
        return when {
            node.kind.equals("UI", ignoreCase = true) -> "UI"
            node.kind.equals("STATE", ignoreCase = true) || "bloc" in lower || "provider" in lower || "riverpod" in lower || "viewmodel" in lower -> "STATE_MGMT"
            node.kind.equals("REPO", ignoreCase = true) || "repository" in lower -> "REPOSITORY"
            node.kind.equals("DATA", ignoreCase = true) || "datasource" in lower || "dao" in lower || "api" in lower -> "DATA"
            node.kind.equals("DI", ignoreCase = true) || "hilt" in lower -> "DI"
            node.kind.equals("CONFIG", ignoreCase = true) -> "CONFIG"
            node.kind.equals("TEST", ignoreCase = true) -> "TEST"
            else -> ROLE_OTHER
        }
    }

    private fun roleKind(role: String): String {
        return when (role) {
            "UI" -> "UI"
            "STATE", "VIEWMODEL", "STATE_MGMT" -> "STATE"
            "REPOSITORY" -> "REPO"
            "DATA", "DATA_SOURCE" -> "DATA"
            "DOMAIN", "USE_CASE" -> "MODEL"
            "DI" -> "DI"
            "CONFIG" -> "CONFIG"
            "TEST" -> "TEST"
            else -> "OTHER"
        }
    }

    private fun isViolationForMode(
        mode: String,
        sourceRole: String,
        targetRole: String,
    ): Boolean {
        return when (mode) {
            "MVVM" -> (sourceRole == "UI" && targetRole in setOf("REPOSITORY", "DATA")) || (sourceRole == "VIEWMODEL" && targetRole == "DATA")
            "Clean" -> sourceRole == "UI" && targetRole in setOf("REPOSITORY", "DATA_SOURCE")
            "State Mgmt" -> sourceRole == "UI" && targetRole in setOf("REPOSITORY", "DATA")
            else -> sourceRole == "UI" && targetRole == "DATA"
        }
    }

    private fun filterGraphPayload(
        base: com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload,
    ): com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload {
        val selectedKind = normalizeFilter(kindFilterSelector.selectedItem?.toString(), "Kind:")
        val selectedSignal = normalizeFilter(signalFilterSelector.selectedItem?.toString(), "Signal:")
        val selectedPlatform = normalizeFilter(platformFilterSelector.selectedItem?.toString(), "Platform:")
        val selectedEdgeType = normalizeFilter(edgeTypeFilterSelector.selectedItem?.toString(), "Edge:")

        val nodesById = base.nodes.associateBy { it.id }
        var allowedNodeIds =
            base.nodes
                .asSequence()
                .filter { selectedKind == null || it.kind.equals(selectedKind, ignoreCase = true) }
                .filter { selectedPlatform == null || it.platform.equals(selectedPlatform, ignoreCase = true) }
                .filter {
                    selectedSignal == null ||
                        it.signals.any { signal -> signal.contains(selectedSignal, ignoreCase = true) }
                }.map { it.id }
                .toMutableSet()

        val edgesByType =
            base.edges
                .asSequence()
                .filter { selectedEdgeType == null || it.edgeType.equals(selectedEdgeType, ignoreCase = true) }
                .toList()

        if (fromSelectedCheck.isSelected) {
            val origin = selectedGraphNodeId?.takeIf { it in nodesById }
            if (origin != null) {
                allowedNodeIds = computeNeighborhood(origin, edgesByType, depthSlider.value).toMutableSet()
            } else {
                allowedNodeIds.clear()
            }
        } else {
            val bounded = boundedByDepth(allowedNodeIds, edgesByType, depthSlider.value)
            allowedNodeIds = bounded.toMutableSet()
        }

        val filteredEdges =
            edgesByType.filter { edge ->
                edge.source in allowedNodeIds && edge.target in allowedNodeIds
            }
        val edgeNodes = filteredEdges.flatMap { listOf(it.source, it.target) }.toSet()
        val finalNodeIds = if (edgeNodes.isNotEmpty()) edgeNodes else allowedNodeIds
        val filteredNodes = base.nodes.filter { it.id in finalNodeIds }
        val fanIn = filteredEdges.groupingBy { it.target }.eachCount()
        val fanOut = filteredEdges.groupingBy { it.source }.eachCount()
        val normalizedNodes =
            filteredNodes.map { node ->
                val inCount = fanIn[node.id] ?: 0
                val outCount = fanOut[node.id] ?: 0
                node.copy(
                    metrics =
                        com.example.open.reviewer.architecture.analysis.CytoscapeGraphNodeMetrics(
                            fanIn = inCount,
                            fanOut = outCount,
                        ),
                    hotspotScore = inCount + outCount,
                )
            }
        return base.copy(
            nodes = normalizedNodes,
            edges = filteredEdges,
            droppedNodeCount = (base.nodes.size - normalizedNodes.size).coerceAtLeast(0),
            droppedEdgeCount = (base.edges.size - filteredEdges.size).coerceAtLeast(0),
            bounded = normalizedNodes.size <= graphPayloadBuilder.limits().maxNodes,
        )
    }

    private fun boundedByDepth(
        seeds: Set<String>,
        edges: List<com.example.open.reviewer.architecture.analysis.CytoscapeGraphEdge>,
        depth: Int,
    ): Set<String> {
        if (seeds.isEmpty() || depth <= 1) return seeds
        val out = linkedSetOf<String>()
        seeds.forEach { seed -> out += computeNeighborhood(seed, edges, depth) }
        return out
    }

    private fun computeNeighborhood(
        origin: String,
        edges: List<com.example.open.reviewer.architecture.analysis.CytoscapeGraphEdge>,
        depth: Int,
    ): Set<String> {
        val adjacency = mutableMapOf<String, MutableSet<String>>()
        edges.forEach { edge ->
            adjacency.getOrPut(edge.source) { linkedSetOf() }.add(edge.target)
            adjacency.getOrPut(edge.target) { linkedSetOf() }.add(edge.source)
        }
        val maxDepth = depth.coerceIn(1, 5)
        val visited = linkedSetOf(origin)
        var frontier = linkedSetOf(origin)
        repeat(maxDepth) {
            val next = linkedSetOf<String>()
            frontier.forEach { node ->
                adjacency[node].orEmpty().forEach { neighbor ->
                    if (visited.add(neighbor)) next += neighbor
                }
            }
            frontier = next
            if (frontier.isEmpty()) return@repeat
        }
        return visited
    }

    private fun normalizeFilter(
        value: String?,
        prefix: String,
    ): String? {
        val raw = value.orEmpty().removePrefix(prefix).trim()
        return raw.takeIf { it.isNotBlank() && !it.equals("ALL", ignoreCase = true) }
    }

    private fun updateNodeDetailsPanel(nodeId: String?) {
        val payload = lastRenderedGraphPayload
        val node = payload?.nodes?.firstOrNull { it.id == nodeId }
        if (payload == null || node == null) {
            nodeDetailsTitle.text = "Node details: none"
            nodeDetailsArea.text = "Select a node in graph to inspect details."
            openNodeFileButton.isEnabled = false
            focusNodeButton.isEnabled = false
            showLayerPathButton.isEnabled = false
            return
        }

        val inbound = payload.edges.filter { it.target == node.id }
        val outbound = payload.edges.filter { it.source == node.id }
        val isVirtualArchitectureNode = node.path.startsWith("__arch__/")
        val rootPath = project.basePath?.let { normalizePath(it) }.orEmpty()
        val cached = latestFileAnalysisByRelative[node.path] ?: latestFileAnalysisByRelative[toRelativePath(node.path, rootPath)]
        val headline = cached?.fileSummary?.headline ?: "No file summary."
        val keyPoints = cached?.fileSummary?.keyPoints.orEmpty().take(4)

        val inboundList =
            inbound.take(8).joinToString("\n") { edge ->
                val from = payload.nodes.firstOrNull { it.id == edge.source }?.label ?: edge.source.substringAfterLast('/')
                "- $from (${edge.edgeType})"
            }.ifBlank { "- none" }
        val outboundList =
            outbound.take(8).joinToString("\n") { edge ->
                val to = payload.nodes.firstOrNull { it.id == edge.target }?.label ?: edge.target.substringAfterLast('/')
                "- $to (${edge.edgeType})"
            }.ifBlank { "- none" }

        nodeDetailsTitle.text = "Node details: ${node.label}"
        nodeDetailsArea.text =
            buildString {
                appendLine("Path: ${node.path}")
                appendLine("Kind/Platform: ${node.kind} / ${node.platform}")
                appendLine("Group: ${node.group}")
                appendLine("FanIn/FanOut: ${node.metrics.fanIn}/${node.metrics.fanOut}")
                appendLine("Signals: ${node.signals.joinToString().ifBlank { "none" }}")
                appendLine()
                if (isVirtualArchitectureNode) {
                    appendLine("Architecture Summary")
                    appendLine("- Aggregated architecture node in ${currentArchitectureViewMode()} mode.")
                    appendLine("- Use inbound/outbound below to inspect architecture flow.")
                } else {
                    appendLine("File Summary")
                    appendLine("- $headline")
                    keyPoints.forEach { appendLine("- $it") }
                }
                appendLine()
                appendLine("Inbound (${inbound.size})")
                appendLine(inboundList)
                appendLine()
                appendLine("Outbound (${outbound.size})")
                appendLine(outboundList)
            }
        nodeDetailsArea.caretPosition = 0

        val pathResult =
            if (pathHighlightModeCheck.isSelected) {
                computeUiPathHighlight(payload = payload, sourceNodeId = node.id)
            } else {
                null
            }
        val highlightNodeIds =
            pathResult?.first ?: buildSet {
                add(node.id)
                inbound.forEach { add(it.source) }
                outbound.forEach { add(it.target) }
            }.toList()
        val highlightEdgeIds = pathResult?.second.orEmpty()
        val detailsSuffix =
            if (pathResult != null) {
                "\n\nPath highlight mode:\n" +
                    "- highlighted nodes: ${highlightNodeIds.size}\n" +
                    "- highlighted edges: ${highlightEdgeIds.size}"
            } else {
                ""
            }
        nodeDetailsArea.text = nodeDetailsArea.text + detailsSuffix
        pushHighlightPayload(highlightNodeIds, highlightEdgeIds)
        openNodeFileButton.isEnabled = !isVirtualArchitectureNode
        focusNodeButton.isEnabled = true
        showLayerPathButton.isEnabled = true
    }

    private fun updateHotspotsPanel(payload: com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload) {
        hotspotsPanel.removeAll()
        val fanInTop =
            payload.nodes
                .sortedWith(compareByDescending<com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode> { it.metrics.fanIn }.thenBy { it.label })
                .take(10)
        val fanOutTop =
            payload.nodes
                .sortedWith(compareByDescending<com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode> { it.metrics.fanOut }.thenBy { it.label })
                .take(10)

        fun addSection(
            title: String,
            nodes: List<com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode>,
            metric: (com.example.open.reviewer.architecture.analysis.CytoscapeGraphNode) -> Int,
        ) {
            hotspotsPanel.add(JBLabel(title).apply { border = JBUI.Borders.empty(2, 8) })
            if (nodes.isEmpty()) {
                hotspotsPanel.add(JBLabel("- none").apply { border = JBUI.Borders.empty(0, 16, 4, 8) })
                return
            }
            nodes.forEach { node ->
                val button =
                    JButton("${node.label} (${metric(node)})").apply {
                        horizontalAlignment = javax.swing.SwingConstants.LEFT
                        border = JBUI.Borders.empty(2, 12, 2, 8)
                        isContentAreaFilled = false
                        addActionListener { focusAndHighlightNeighborhood(node.id) }
                    }
                hotspotsPanel.add(button)
            }
        }

        addSection("Top fan-in", fanInTop) { it.metrics.fanIn }
        addSection("Top fan-out", fanOutTop) { it.metrics.fanOut }
        hotspotsPanel.revalidate()
        hotspotsPanel.repaint()
    }

    private fun focusAndHighlightNeighborhood(nodeId: String) {
        val payload = lastRenderedGraphPayload ?: return
        val inbound = payload.edges.filter { it.target == nodeId }
        val outbound = payload.edges.filter { it.source == nodeId }
        val nodeIds =
            buildSet {
                add(nodeId)
                inbound.forEach { add(it.source) }
                outbound.forEach { add(it.target) }
            }.toList()
        val edgeIds = (inbound + outbound).map { it.id }
        selectedGraphNodeId = nodeId
        pushFocusNode(nodeId)
        pushHighlightPayload(nodeIds = nodeIds, edgeIds = edgeIds)
        updateNodeDetailsPanel(nodeId)
    }

    private fun computeUiPathHighlight(
        payload: com.example.open.reviewer.architecture.analysis.CytoscapeGraphPayload,
        sourceNodeId: String,
    ): Pair<List<String>, List<String>> {
        val source = payload.nodes.firstOrNull { it.id == sourceNodeId } ?: return emptyList<String>() to emptyList()
        if (!source.kind.equals("UI", ignoreCase = true)) return emptyList<String>() to emptyList()

        val targetKinds = linkedSetOf("STATE", "REPO", "DATA")
        val kindById = payload.nodes.associate { it.id to it.kind.uppercase() }
        val outgoing = linkedMapOf<String, MutableList<com.example.open.reviewer.architecture.analysis.CytoscapeGraphEdge>>()
        payload.edges.forEach { edge ->
            outgoing.getOrPut(edge.source) { mutableListOf() }.add(edge)
        }

        data class Prev(
            val node: String,
            val edgeId: String,
        )

        val queue = ArrayDeque<String>()
        val visited = linkedSetOf<String>()
        val prevByNode = linkedMapOf<String, Prev>()
        val foundByKind = linkedMapOf<String, String>()
        queue.add(sourceNodeId)
        visited.add(sourceNodeId)
        var traversedEdges = 0

        while (queue.isNotEmpty() && foundByKind.size < targetKinds.size && traversedEdges <= PATH_HIGHLIGHT_EDGE_VISIT_CAP) {
            val current = queue.removeFirst()
            outgoing[current].orEmpty().forEach { edge ->
                traversedEdges += 1
                val next = edge.target
                if (visited.add(next)) {
                    prevByNode[next] = Prev(current, edge.id)
                    queue.add(next)
                    val kind = kindById[next]
                    if (kind != null && kind in targetKinds && kind !in foundByKind) {
                        foundByKind[kind] = next
                    }
                }
            }
        }

        val highlightNodes = linkedSetOf(sourceNodeId)
        val highlightEdges = linkedSetOf<String>()
        foundByKind.values.forEach { target ->
            var cursor = target
            highlightNodes += cursor
            while (cursor != sourceNodeId) {
                val prev = prevByNode[cursor] ?: break
                highlightEdges += prev.edgeId
                highlightNodes += prev.node
                cursor = prev.node
            }
        }
        return highlightNodes.toList() to highlightEdges.toList()
    }

    private fun openSelectedGraphNodeFile() {
        val nodeId = selectedGraphNodeId ?: return
        val node = lastRenderedGraphPayload?.nodes?.firstOrNull { it.id == nodeId } ?: return
        if (node.path.startsWith("__arch__/")) return
        val relative = node.path.removePrefix("entrypoint:")
        val basePath = project.basePath ?: return
        val abs = Path.of(basePath).resolve(relative).normalize().toString()
        val file = LocalFileSystem.getInstance().findFileByPath(abs) ?: return
        OpenFileDescriptor(project, file).let { descriptor ->
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }

    private fun focusOnSelectedNodeNeighborhood() {
        val nodeId = selectedGraphNodeId ?: return
        fromSelectedCheck.isSelected = true
        pushFocusNode(nodeId)
        applyJvmFiltersAndRender()
    }

    private fun showPathToLayerForSelectedNode() {
        val nodeId = selectedGraphNodeId ?: return
        val payload = lastRenderedGraphPayload ?: return
        val node = payload.nodes.firstOrNull { it.id == nodeId } ?: return
        val kindPath =
            when (node.kind.uppercase()) {
                "UI" -> "UI"
                "STATE" -> "UI -> STATE"
                "REPO" -> "UI -> STATE -> REPO"
                "DI" -> "UI -> DI"
                "DATA" -> "UI -> STATE -> REPO -> DATA"
                else -> "UI -> ..."
            }
        val prefix =
            if (nodeDetailsArea.text.isBlank()) ""
            else nodeDetailsArea.text + "\n\n"
        nodeDetailsArea.text = prefix + "Layer path: $kindPath"
        nodeDetailsArea.caretPosition = maxOf(0, nodeDetailsArea.text.length - min(nodeDetailsArea.text.length, 120))
    }

    private fun toRelativePath(
        path: String,
        root: String,
    ): String {
        val normalizedPath = normalizePath(path)
        val rootPrefix = if (root.endsWith("/")) root else "$root/"
        return normalizedPath.removePrefix(rootPrefix)
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/')

    private fun inferKindForFallback(path: String): String {
        val lower = path.lowercase()
        return when {
            "viewmodel" in lower || "/state/" in lower || "bloc" in lower || "provider" in lower -> "STATE"
            "/ui/" in lower || "activity" in lower || "screen" in lower || "widget" in lower -> "UI"
            "repository" in lower || "/repo/" in lower -> "REPO"
            "/di/" in lower || "hilt" in lower || "module" in lower -> "DI"
            "model" in lower || "entity" in lower || "dto" in lower -> "MODEL"
            "manifest" in lower || "gradle" in lower || "config" in lower -> "CONFIG"
            "test" in lower -> "TEST"
            else -> "OTHER"
        }
    }

    private fun inferPlatformForFallback(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".dart") -> "flutter"
            lower.endsWith(".kt") || lower.endsWith(".java") || "manifest" in lower -> "android"
            else -> "shared"
        }
    }

    private fun extractJsonString(
        json: String,
        key: String,
    ): String? {
        val regex = Regex("""\"$key\"\s*:\s*\"([^\"]*)\"""")
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    private fun toJsString(value: String): String {
        val escaped =
            buildString {
                value.forEach { ch ->
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
            }
        return "\"$escaped\""
    }

    private fun clearCache() {
        fileCache.clearCache()
        overviewSummaryArea.text = "Architecture file cache cleared."
        highLevelDiagramArea.text = "Architecture file cache cleared."
        updateOverviewChips(listOf("Cache cleared"))
        updateEvidenceTable(emptyList())
        evidenceArea.text = "Architecture file cache cleared."
        val message = "Architecture file cache cleared."
        latestFileAnalysisByRelative = emptyMap()
        selectedGraphNodeId = null
        updateGraphView(message, buildFallbackGraphPayloadModel(message))
        deepStatusLabel.text = "Cache cleared."
        statusLabel.text = "Ready"
    }

    private data class DisplayPattern(
        val name: String,
        val confidence: Double,
    )

    private data class EvidenceRow(
        val pattern: String,
        val confidence: String,
        val signals: String,
        val files: String,
    )

    companion object {
        private const val GRAPH_CARD_JCEF = "jcef"
        private const val GRAPH_CARD_FALLBACK = "fallback"
        private const val CLUSTER_ALL_ID = "__all__"
        private const val FILTER_REFINE_NODE_LIMIT = 550
        private const val PATH_HIGHLIGHT_EDGE_VISIT_CAP = 12_000
        private const val MAX_FEATURE_GROUPS = 14
        private const val ROLE_OTHER = "OTHER"
    }

    private enum class GraphBridgeCommandType {
        SET_GRAPH,
        APPLY_FILTERS,
        FOCUS_NODE,
        SET_HIGHLIGHT,
        EXPAND_CLUSTER,
        COLLAPSE_CLUSTER,
        SET_THEME,
    }

    private enum class GraphBridgeEventType {
        READY,
        NODE_CLICK,
        NODE_DOUBLE_CLICK,
        SELECTION_CHANGED,
        VIEWPORT_CHANGED,
    }

}
