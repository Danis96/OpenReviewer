package com.example.open.reviewer.toolwindow

import com.example.open.reviewer.analysis.RiskLevel
import com.example.open.reviewer.analysis.StartupAnalysisResult
import com.example.open.reviewer.analysis.StartupAnalysisService
import com.example.open.reviewer.analysis.Suggestion
import com.example.open.reviewer.editor.EditorSuggestionDecorationService
import com.example.open.reviewer.settings.OpenReviewerSettingsConfigurable
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.DefaultListSelectionModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextPane
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class OpenReviewerToolWindowContent(
    private val project: Project,
) : JBPanel<OpenReviewerToolWindowContent>(BorderLayout()) {
    private val analysisService = StartupAnalysisService.getInstance(project)
    private val editorSuggestionDecorationService = EditorSuggestionDecorationService.getInstance(project)

    private val analyzeButton = JButton("Analyze Startup", AllIcons.Actions.Execute)
    private val configureApiButton = JButton("Configure API", AllIcons.General.GearPlain)

    private val riskLevelLabel = JBLabel("Risk Level")
    private val riskLevelValueLabel = JBLabel("--")
    private val riskProgressBar = JProgressBar(0, 100)
    private val lowRiskLabel = JBLabel("Low Risk")
    private val highRiskLabel = JBLabel("High Risk")
    private val scoreLabel = JBLabel("Score: --/100")
    private val scoreCard =
        RoundedInfoCard().apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(12, 12, 12, 12)
            isVisible = false
        }
    private val scoreCardLabel =
        JBLabel("Score:").apply {
            font = JBFont.label().asBold()
        }
    private val scoreCardValue =
        JBLabel("--/100").apply {
            font = JBFont.label().asBold()
        }
    private val aiScoreSubLabel =
        JBLabel("AI risk score: --/100").apply {
            foreground = UIUtil.getContextHelpForeground()
        }
    private val breakdownTitle =
        JBLabel("Score Breakdown").apply {
            font = JBFont.label().asBold()
            isVisible = false
        }
    private val breakdownGrid =
        JPanel(GridLayout(2, 2, 8, 8)).apply {
            isOpaque = false
            isVisible = false
        }
    private val baseScoreValue = JBLabel("--")
    private val modelScoreValue = JBLabel("--")
    private val deltaScoreValue = JBLabel("--")
    private val finalScoreValue = JBLabel("--")
    private val statusRowCard =
        RoundedInfoCard().apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(10, 12, 10, 12)
            isVisible = false
        }
    private val statusValueLabel =
        JBLabel("--").apply {
            font = JBFont.label().asBold()
        }
    private val confidenceRowCard =
        RoundedInfoCard().apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(10, 12, 10, 12)
            isVisible = false
        }
    private val confidenceValueLabel =
        JBLabel("--").apply {
            font = JBFont.label().asBold()
        }

    private val summaryCardInner =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
    private val summaryCard =
        object : RoundedInfoCard() {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

            override fun getPreferredSize(): Dimension {
                val ps = super.getPreferredSize()
                val parentWidth = parent?.width ?: 0
                return Dimension(if (parentWidth > 0) parentWidth else ps.width, ps.height)
            }
        }.apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(12)
            isVisible = false
            alignmentX = LEFT_ALIGNMENT
            add(summaryCardInner, BorderLayout.CENTER)
        }

    private val summaryTitleLabel =
        JBLabel("Summary").apply {
            font = JBFont.label().asBold()
            foreground = UIUtil.getContextHelpForeground()
            alignmentX = LEFT_ALIGNMENT
        }
    private val summaryFactorsPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    private val aiRiskSummaryPane =
        object : JTextPane() {
            override fun getScrollableTracksViewportWidth(): Boolean = true
        }.apply {
            isEditable = false
            isOpaque = false
            isFocusable = false
            font = JBFont.label().asPlain()
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty()
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    private val summaryBodyStyle =
        SimpleAttributeSet().apply {
            StyleConstants.setFontFamily(this, JBFont.label().family)
            StyleConstants.setFontSize(this, JBFont.label().size + 1)
            StyleConstants.setForeground(this, UIUtil.getLabelForeground())
            StyleConstants.setLineSpacing(this, 0.08f)
        }
    private val summaryCodeStyle =
        SimpleAttributeSet().apply {
            StyleConstants.setFontFamily(this, "JetBrains Mono")
            StyleConstants.setFontSize(this, JBFont.label().size + 1)
            StyleConstants.setForeground(this, JBColor(Color(0xE28A3B), Color(0xE28A3B)))
            StyleConstants.setBold(this, true)
        }

    private val suggestionsHeaderLabel = JBLabel("0")
    private val suggestionsModel = DefaultListModel<Suggestion>()
    private val suggestionsList = JBList(suggestionsModel)
    private val entryPointsTitleLabel =
        JBLabel("Analyzed Entry Points").apply {
            font = JBFont.label().asBold()
            foreground = UIUtil.getContextHelpForeground()
        }
    private val entryPointsFlow =
        JPanel(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
            isOpaque = false
        }

    init {
        border = JBUI.Borders.empty(8)

        val main = JBPanel<JBPanel<*>>()
        main.layout = BoxLayout(main, BoxLayout.Y_AXIS)
        main.isOpaque = false
        main.add(createTopActionsRow())
        main.add(createSpacer(8))
        main.add(createRiskScoreSection())
        main.add(createSpacer(8))
        main.add(createSuggestionsSection())

        val rootScroll = JBScrollPane(main)
        rootScroll.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        rootScroll.border = JBUI.Borders.empty()
        rootScroll.viewportBorder = JBUI.Borders.empty()
        rootScroll.isOpaque = false
        rootScroll.viewport.isOpaque = false

        add(rootScroll, BorderLayout.CENTER)
    }

    private fun createTopActionsRow(): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        row.isOpaque = false

        analyzeButton.putClientProperty("JButton.defaultButton", true)
        analyzeButton.addActionListener { runStartupAnalysis() }
        configureApiButton.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, OpenReviewerSettingsConfigurable::class.java)
        }

        row.add(analyzeButton)
        row.add(configureApiButton)
        return row
    }

    private fun createRiskScoreSection(): JComponent {
        // GridBagLayout guarantees every child stretches to full width (fill=HORIZONTAL, weightx=1.0).
        // This avoids all BoxLayout X-alignment issues that caused the summary card to render at half width.
        val content = JBPanel<JBPanel<*>>(java.awt.GridBagLayout())
        content.border = JBUI.Borders.empty(8, 0)

        var row = 0

        fun gbc(topGap: Int = 0) =
            java.awt.GridBagConstraints().apply {
                gridx = 0
                gridy = row++
                fill = java.awt.GridBagConstraints.HORIZONTAL
                weightx = 1.0
                weighty = 0.0
                insets = java.awt.Insets(topGap, 0, 0, 0)
            }

        val riskHeaderRow =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(riskLevelLabel, BorderLayout.WEST)
                add(riskLevelValueLabel, BorderLayout.EAST)
            }
        riskLevelLabel.font = JBFont.label().asBold()
        riskLevelValueLabel.font = JBFont.label().asBold()
        riskLevelValueLabel.horizontalAlignment = SwingConstants.RIGHT

        riskProgressBar.value = 0
        riskProgressBar.isStringPainted = false
        riskProgressBar.foreground = riskColorFor(RiskLevel.LOW)

        val riskRangeRow =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(lowRiskLabel, BorderLayout.WEST)
                add(highRiskLabel, BorderLayout.EAST)
            }
        lowRiskLabel.foreground = UIUtil.getContextHelpForeground()
        highRiskLabel.foreground = UIUtil.getContextHelpForeground()
        scoreLabel.horizontalAlignment = SwingConstants.LEFT

        val scoreCardHeader =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(scoreCardLabel, BorderLayout.WEST)
                add(scoreCardValue, BorderLayout.EAST)
            }
        val scoreCardContent =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(scoreCardHeader)
                add(createStaticSpacer(8))
                add(aiScoreSubLabel)
            }
        scoreCard.add(scoreCardContent, BorderLayout.CENTER)

        breakdownGrid.add(createMetricTile("Base score", baseScoreValue))
        breakdownGrid.add(createMetricTile("Model score", modelScoreValue))
        breakdownGrid.add(createMetricTile("Applied delta", deltaScoreValue))
        breakdownGrid.add(createMetricTile("Final score", finalScoreValue))

        val statusRight =
            JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(JBLabel(AllIcons.General.InspectionsOK))
                add(statusValueLabel)
            }
        statusRowCard.add(JBLabel("Status").apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.WEST)
        statusRowCard.add(statusRight, BorderLayout.EAST)

        confidenceRowCard.add(JBLabel("Confidence").apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.WEST)
        confidenceRowCard.add(confidenceValueLabel, BorderLayout.EAST)

        summaryCardInner.removeAll()
        summaryCardInner.add(summaryTitleLabel)
        summaryCardInner.add(createStaticSpacer(8))
        summaryCardInner.add(aiRiskSummaryPane)
        summaryCardInner.add(createStaticSpacer(8))
        summaryCardInner.add(summaryFactorsPanel)

        updateEntryPointChips(emptyList())
        val entryPointsContainer =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(entryPointsFlow, BorderLayout.CENTER)
            }

        content.add(entryPointsTitleLabel, gbc())
        content.add(entryPointsContainer, gbc(4))
        content.add(createSpacer(8), gbc(8))
        content.add(riskHeaderRow, gbc())
        content.add(createSpacer(6), gbc(6))
        content.add(riskProgressBar, gbc())
        content.add(createSpacer(4), gbc(4))
        content.add(riskRangeRow, gbc())
        content.add(createSpacer(6), gbc(6))
        content.add(scoreLabel, gbc())
        content.add(createSpacer(8), gbc(8))
        content.add(scoreCard, gbc())
        content.add(createSpacer(10), gbc(10))
        content.add(breakdownTitle, gbc())
        content.add(createSpacer(8), gbc(8))
        content.add(breakdownGrid, gbc())
        content.add(createSpacer(8), gbc(8))
        content.add(statusRowCard, gbc())
        content.add(createSpacer(8), gbc(8))
        content.add(confidenceRowCard, gbc())
        content.add(createSpacer(8), gbc(8))
        content.add(summaryCard, gbc())

        // Vertical glue so rows don't spread when the panel is taller than its content
        content.add(
            JPanel().apply { isOpaque = false },
            java.awt.GridBagConstraints().apply {
                gridx = 0
                gridy = row
                fill = java.awt.GridBagConstraints.VERTICAL
                weighty = 1.0
            },
        )

        return createCollapsibleSection("Performance Risk Score", content)
    }

    private fun createSuggestionsSection(): JComponent {
        val content = JBPanel<JBPanel<*>>(BorderLayout())
        content.border = JBUI.Borders.empty(8, 0)

        suggestionsList.cellRenderer = SuggestionCardRenderer()
        suggestionsList.selectionModel = NoSelectionModel()
        suggestionsList.isFocusable = false
        suggestionsList.setExpandableItemsEnabled(false)
        suggestionsList.fixedCellHeight = -1
        suggestionsList.visibleRowCount = -1
        suggestionsList.background = UIUtil.getPanelBackground()
        suggestionsList.selectionBackground = UIUtil.getPanelBackground()
        suggestionsList.selectionForeground = UIUtil.getLabelForeground()
        suggestionsList.addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) = suggestionsList.clearSelection()

                override fun mouseReleased(e: java.awt.event.MouseEvent) = suggestionsList.clearSelection()

                override fun mouseClicked(e: java.awt.event.MouseEvent) = suggestionsList.clearSelection()
            },
        )
        suggestionsList.addMouseMotionListener(
            object : java.awt.event.MouseMotionAdapter() {
                override fun mouseMoved(e: java.awt.event.MouseEvent) = suggestionsList.clearSelection()

                override fun mouseDragged(e: java.awt.event.MouseEvent) = suggestionsList.clearSelection()
            },
        )
        suggestionsList.emptyText.text = "No suggestions yet. Run Analyze Startup."
        suggestionsList.border = JBUI.Borders.empty()
        suggestionsList.isOpaque = false

        content.add(suggestionsList, BorderLayout.CENTER)

        suggestionsHeaderLabel.font = JBFont.label().asBold()
        suggestionsHeaderLabel.foreground = UIUtil.getContextHelpForeground()

        updateSuggestions(emptyList())
        return createCollapsibleSection("AI Suggestions", content, suggestionsHeaderLabel)
    }

    private fun runStartupAnalysis() {
        analyzeButton.isEnabled = false

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Analyze Startup", true) {
                override fun run(indicator: ProgressIndicator) {
                    val result = analysisService.analyzeStartup(indicator)
                    ApplicationManager.getApplication().invokeLater {
                        applyAnalysisResult(result)
                        analyzeButton.isEnabled = true
                    }
                }

                override fun onThrowable(error: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        analyzeButton.isEnabled = true
                        showAnalysisFailure(error)
                    }
                }

                override fun onCancel() {
                    ApplicationManager.getApplication().invokeLater {
                        analyzeButton.isEnabled = true
                    }
                }
            },
        )
    }

    private fun applyAnalysisResult(result: StartupAnalysisResult) {
        riskProgressBar.value = result.riskScore
        riskLevelValueLabel.text = result.riskLevel.name.lowercase().replaceFirstChar { it.uppercase() }
        riskLevelValueLabel.foreground = riskColorFor(result.riskLevel)
        riskProgressBar.foreground = riskColorFor(result.riskLevel)
        scoreLabel.text = "Score: ${result.riskScore}/100"
        scoreLabel.horizontalAlignment = SwingConstants.LEFT
        scoreLabel.alignmentX = LEFT_ALIGNMENT
        updateEntryPointChips(result.analyzedEntryPoints)
        updateAiRiskBadge(result)
        updateSuggestions(result.suggestions)
        editorSuggestionDecorationService.showSuggestions(result.suggestions)
    }

    private fun updateAiRiskBadge(result: StartupAnalysisResult) {
        val aiRisk = result.aiRisk
        if (!aiRisk.enabled || !aiRisk.used) {
            scoreCard.isVisible = false
            breakdownTitle.isVisible = false
            breakdownGrid.isVisible = false
            statusRowCard.isVisible = false
            confidenceRowCard.isVisible = false
            summaryCard.isVisible = false
            summaryCardInner.isVisible = false
            summaryFactorsPanel.removeAll()
            aiRiskSummaryPane.styledDocument.remove(0, aiRiskSummaryPane.styledDocument.length)
            return
        }

        val finalScore = result.riskScore
        val modelScore = aiRisk.modelRiskScore ?: finalScore
        scoreCardValue.text = "$finalScore/100"
        scoreCardValue.foreground = riskColorFor(result.riskLevel)
        aiScoreSubLabel.text = "AI risk score: $modelScore/100 (${((aiRisk.confidence ?: 0.0) * 100).toInt()}%)"

        baseScoreValue.text = aiRisk.baseRiskScore.toString()
        modelScoreValue.text = modelScore.toString()
        deltaScoreValue.text = "${if (aiRisk.appliedDelta > 0) "+" else ""}${aiRisk.appliedDelta}"
        finalScoreValue.text = finalScore.toString()
        deltaScoreValue.foreground = if (aiRisk.appliedDelta >= 0) riskColorFor(RiskLevel.MEDIUM) else riskColorFor(RiskLevel.LOW)
        finalScoreValue.foreground = riskColorFor(result.riskLevel)

        statusValueLabel.text = aiRisk.parseStatus.name
        statusValueLabel.foreground = statusColor()
        confidenceValueLabel.text = "${((aiRisk.confidence ?: 0.0) * 100).toInt()}%"
        confidenceValueLabel.foreground = UIUtil.getLabelForeground()

        applySummaryStyling(aiRisk.summary?.trim().orEmpty())
        summaryFactorsPanel.removeAll()
        aiRisk.adjustments.take(3).forEach { adjustment ->
            summaryFactorsPanel.add(
                JBLabel(
                    "↗  ${adjustment.factor}: ${if (adjustment.delta > 0) {
                        "+"
                    } else {
                        ""
                    }}${adjustment.delta} @ ${(adjustment.confidence * 100).toInt()}%",
                ).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.emptyBottom(4)
                    alignmentX = LEFT_ALIGNMENT
                },
            )
        }
        if (summaryFactorsPanel.componentCount == 0) {
            summaryFactorsPanel.add(
                JBLabel("No explicit factor adjustments.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    alignmentX = LEFT_ALIGNMENT
                },
            )
        }

        scoreCard.isVisible = true
        breakdownTitle.isVisible = true
        breakdownGrid.isVisible = true
        statusRowCard.isVisible = true
        confidenceRowCard.isVisible = true
        summaryCardInner.isVisible = true
        summaryCard.isVisible = true
        revalidate()
        repaint()
    }

    private fun applySummaryStyling(text: String) {
        val doc = aiRiskSummaryPane.styledDocument
        doc.remove(0, doc.length)

        if (text.isBlank()) return
        val inlineCodeRegex = Regex("`([^`]+)`")
        var cursor = 0
        inlineCodeRegex.findAll(text).forEach { match ->
            val start = match.range.first
            if (start > cursor) {
                doc.insertString(doc.length, text.substring(cursor, start), summaryBodyStyle)
            }
            doc.insertString(doc.length, match.groupValues[1], summaryCodeStyle)
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            doc.insertString(doc.length, text.substring(cursor), summaryBodyStyle)
        }
    }

    private fun riskColorFor(level: RiskLevel): Color {
        return when (level) {
            RiskLevel.LOW -> JBColor(Color(0x4D8DFF), Color(0x4D8DFF))
            RiskLevel.MEDIUM -> JBColor(Color(0xF0B24B), Color(0xF0B24B))
            RiskLevel.HIGH -> JBColor(Color(0xE86A5F), Color(0xE86A5F))
        }
    }

    private fun statusColor(): Color = JBColor(Color(0x63BE84), Color(0x63BE84))

    private fun createStaticSpacer(height: Int): JComponent {
        return JPanel().apply {
            isOpaque = false
            preferredSize = Dimension(1, height)
            maximumSize = Dimension(Int.MAX_VALUE, height)
            alignmentX = LEFT_ALIGNMENT
        }
    }

    private fun createLeftAlignedRow(component: JComponent): JComponent {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, component.preferredSize.height + 2)
            alignmentX = LEFT_ALIGNMENT
            if (component is JBLabel) {
                component.horizontalAlignment = SwingConstants.LEFT
            }
            add(component, BorderLayout.CENTER)
        }
    }

    private fun createMetricTile(
        title: String,
        valueLabel: JBLabel,
    ): JComponent {
        valueLabel.font = JBFont.label().deriveFont((JBFont.label().size + 6).toFloat())
        valueLabel.foreground = UIUtil.getLabelForeground()
        val titleLabel = JBLabel(title).apply { foreground = UIUtil.getContextHelpForeground() }
        return RoundedInfoCard().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12, 12, 12, 12)
            add(titleLabel)
            add(createStaticSpacer(8))
            add(valueLabel)
        }
    }

    private fun updateSuggestions(suggestions: List<Suggestion>) {
        suggestionsModel.clear()
        suggestionsHeaderLabel.text = suggestions.size.toString()

        suggestions.forEach { suggestionsModel.addElement(it) }
        suggestionsList.clearSelection()
        suggestionsList.revalidate()
        suggestionsList.repaint()
    }

    private fun updateEntryPointChips(entryPoints: List<String>) {
        entryPointsFlow.removeAll()
        val names = entryPoints.distinct()
        if (names.isEmpty()) {
            entryPointsFlow.add(
                JBLabel("No startup entry points detected.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                },
            )
        } else {
            names.forEach { entryPointsFlow.add(createEntryPointChip(it)) }
        }
        entryPointsFlow.revalidate()
        entryPointsFlow.repaint()
    }

    private fun createEntryPointChip(fileName: String): JComponent {
        val tone = chipToneFor(fileName)
        val textLabel =
            JBLabel(fileName).apply {
                font = JBFont.label().asBold()
                foreground = tone.text
            }

        val iconLabel =
            JLabel(AllIcons.FileTypes.Any_type).apply {
                foreground = tone.text
                border = JBUI.Borders.emptyRight(8)
            }

        return object : JBPanel<JBPanel<*>>(BorderLayout()) {
            private val arc = 10

            init {
                isOpaque = false
                border = JBUI.Borders.empty(6, 10, 6, 10)
                add(iconLabel, BorderLayout.WEST)
                add(textLabel, BorderLayout.CENTER)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = tone.fill
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.dispose()
                super.paintComponent(g)
            }

            override fun paintBorder(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = tone.border
                g2.stroke = BasicStroke(1f)
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.dispose()
            }
        }
    }

    private fun chipToneFor(fileName: String): ChipTone {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "dart" ->
                ChipTone(
                    fill = JBColor(Color(0xEAF4FF), Color(0x1E3A5A)),
                    border = JBColor(Color(0x7CB8F2), Color(0x1B6EB8)),
                    text = JBColor(Color(0x0B66B7), Color(0x25A6FF)),
                )
            "swift" ->
                ChipTone(
                    fill = JBColor(Color(0xFFEDEA), Color(0x4A2F32)),
                    border = JBColor(Color(0xF0A096), Color(0xB04A3E)),
                    text = JBColor(Color(0xC6492D), Color(0xFF5C3A)),
                )
            "kt", "kts", "java" ->
                ChipTone(
                    fill = JBColor(Color(0xF0EBFF), Color(0x352C59)),
                    border = JBColor(Color(0xB3A0EF), Color(0x6C52C7)),
                    text = JBColor(Color(0x5A3EC2), Color(0x7B5CFF)),
                )
            else ->
                ChipTone(
                    fill = JBColor(Color(0xEFF2F6), Color(0x333842)),
                    border = JBColor(Color(0xBCC5D3), Color(0x566076)),
                    text = JBColor(Color(0x3F4D67), UIUtil.getLabelForeground()),
                )
        }
    }

    private fun showAnalysisFailure(error: Throwable) {
        editorSuggestionDecorationService.clearAll()
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Open Reviewer Notifications")
            .createNotification(
                "Open Reviewer analysis failed",
                error.message ?: "Unexpected error while running startup analysis.",
                NotificationType.ERROR,
            )
            .notify(project)
    }

    private fun createSpacer(height: Int): JComponent {
        val spacer = JPanel()
        spacer.isOpaque = false
        spacer.preferredSize = Dimension(1, height)
        spacer.maximumSize = Dimension(Int.MAX_VALUE, height)
        return spacer
    }

    private fun createCollapsibleSection(
        title: String,
        content: JComponent,
    ): JComponent {
        return createCollapsibleSection(title, content, null)
    }

    private fun createCollapsibleSection(
        title: String,
        content: JComponent,
        trailing: JComponent?,
    ): JComponent {
        val section = JBPanel<JBPanel<*>>(BorderLayout())

        val header = JPanel(BorderLayout())
        header.isOpaque = false

        val toggleButton = JButton(title, AllIcons.General.ArrowDown)
        toggleButton.horizontalAlignment = SwingConstants.LEFT
        toggleButton.border = JBUI.Borders.empty(0, 0, 0, 0)
        toggleButton.isBorderPainted = false
        toggleButton.isContentAreaFilled = false
        toggleButton.isOpaque = false
        toggleButton.horizontalTextPosition = SwingConstants.RIGHT
        toggleButton.iconTextGap = 6

        toggleButton.addActionListener {
            content.isVisible = !content.isVisible
            toggleButton.icon = if (content.isVisible) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
            section.revalidate()
            section.repaint()
        }

        header.add(toggleButton, BorderLayout.WEST)
        if (trailing != null) {
            header.add(trailing, BorderLayout.EAST)
        }

        section.add(header, BorderLayout.NORTH)
        section.add(content, BorderLayout.CENTER)
        return section
    }

    private class RoundedSuggestionCard : JBPanel<RoundedSuggestionCard>() {
        private val arc = 14

        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fillColor()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }

        override fun paintBorder(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = strokeColor()
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
        }

        private fun fillColor(): Color {
            val base = UIUtil.getPanelBackground()
            return if (!JBColor.isBright()) ColorUtil.shift(base, 1.08) else ColorUtil.shift(base, 0.985)
        }

        private fun strokeColor(): Color {
            val base = UIUtil.getPanelBackground()
            return if (!JBColor.isBright()) ColorUtil.shift(base, 1.22) else ColorUtil.shift(base, 0.9)
        }
    }

    private open class RoundedInfoCard : JBPanel<RoundedInfoCard>() {
        private val arc = 12

        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fillColor()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }

        override fun paintBorder(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = strokeColor()
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
        }

        private fun fillColor(): Color {
            val base = UIUtil.getPanelBackground()
            return if (!JBColor.isBright()) ColorUtil.shift(base, 1.12) else ColorUtil.shift(base, 0.98)
        }

        private fun strokeColor(): Color {
            val base = UIUtil.getPanelBackground()
            return if (!JBColor.isBright()) ColorUtil.shift(base, 1.26) else ColorUtil.shift(base, 0.9)
        }
    }

    private class SuggestionCardRenderer : ListCellRenderer<Suggestion> {
        private val card =
            RoundedSuggestionCard().apply {
                layout = BorderLayout(12, 0)
                border = JBUI.Borders.empty(14, 14, 14, 14)
            }
        private val icon =
            JBLabel(AllIcons.Actions.IntentionBulb).apply {
                verticalAlignment = SwingConstants.TOP
                border = JBUI.Borders.emptyTop(2)
            }
        private val textPane =
            object : JTextPane() {
                override fun getScrollableTracksViewportWidth(): Boolean = true
            }.apply {
                isEditable = false
                isOpaque = false
                isFocusable = false
                font = JBFont.label().asPlain()
                foreground = UIUtil.getLabelForeground()
                border = JBUI.Borders.empty()
            }
        private val bodyStyle =
            SimpleAttributeSet().apply {
                StyleConstants.setFontFamily(this, JBFont.label().family)
                StyleConstants.setFontSize(this, JBFont.label().size)
                StyleConstants.setLineSpacing(this, 0.08f)
            }
        private val inlineCodeStyle =
            SimpleAttributeSet().apply {
                StyleConstants.setFontFamily(this, "JetBrains Mono")
                StyleConstants.setFontSize(this, JBFont.label().size - 1)
                StyleConstants.setBold(this, true)
            }
        private val wrapper =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(8)
                add(card, BorderLayout.CENTER)
            }

        init {
            card.add(icon, BorderLayout.WEST)
            card.add(textPane, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out Suggestion>,
            value: Suggestion,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val text = if (value.details.isNotBlank()) value.details.trim() else value.title.trim()
            StyleConstants.setForeground(bodyStyle, UIUtil.getLabelForeground())
            StyleConstants.setForeground(
                inlineCodeStyle,
                if (isDarkTheme()) JBColor(Color(0xD8E8FF), Color(0xD8E8FF)) else JBColor(Color(0x163A67), Color(0x163A67)),
            )
            StyleConstants.setBackground(inlineCodeStyle, inlineCodeBackground())
            textPane.foreground = UIUtil.getLabelForeground()
            applyInlineCodeStyling(text)
            val available = (list.width - 96).coerceAtLeast(160)
            textPane.setSize(available, Int.MAX_VALUE)

            return wrapper
        }

        private fun applyInlineCodeStyling(text: String) {
            val doc = textPane.styledDocument
            doc.remove(0, doc.length)

            val inlineCodeRegex = Regex("`([^`]+)`")
            var cursor = 0
            inlineCodeRegex.findAll(text).forEach { match ->
                val start = match.range.first
                if (start > cursor) {
                    doc.insertString(doc.length, text.substring(cursor, start), bodyStyle)
                }
                doc.insertString(doc.length, match.groupValues[1], inlineCodeStyle)
                cursor = match.range.last + 1
            }

            if (cursor < text.length) {
                doc.insertString(doc.length, text.substring(cursor), bodyStyle)
            }
        }

        private fun inlineCodeBackground(): Color {
            val base = UIUtil.getPanelBackground()
            return if (isDarkTheme()) ColorUtil.shift(base, 1.22) else ColorUtil.shift(base, 0.93)
        }

        private fun isDarkTheme(): Boolean = !JBColor.isBright()
    }

    private class NoSelectionModel : DefaultListSelectionModel() {
        override fun setSelectionInterval(
            index0: Int,
            index1: Int,
        ) = Unit

        override fun addSelectionInterval(
            index0: Int,
            index1: Int,
        ) = Unit
    }

    private data class ChipTone(
        val fill: Color,
        val border: Color,
        val text: Color,
    )
}
