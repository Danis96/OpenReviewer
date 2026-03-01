package com.example.open.reviewer.commitchecklist.ui

import com.example.open.reviewer.commitchecklist.spec.ChecklistSpec
import com.example.open.reviewer.commitchecklist.spec.CommitChecklistSpecParser
import com.example.open.reviewer.commitchecklist.spec.CommitChecklistSpecService
import com.example.open.reviewer.commitchecklist.setup.CommitChecklistSpecInstallWorkflow
import com.example.open.reviewer.commitchecklist.template.GitHubPrTemplateDetector
import com.example.open.reviewer.commitchecklist.template.GitLabMrTemplateDetector
import com.example.open.reviewer.commitchecklist.template.TemplateDiffBuilder
import com.example.open.reviewer.commitchecklist.template.TemplateDiffPreviewDialog
import com.example.open.reviewer.commitchecklist.vcs.CommitChecklistVcsService
import com.example.open.reviewer.commitchecklist.vcs.HostingPlatform
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class RepoSetupStatusPanel(
    private val project: Project,
) : JBPanel<RepoSetupStatusPanel>(BorderLayout()) {

    private val vcsService = CommitChecklistVcsService.getInstance(project)
    private val specService = CommitChecklistSpecService.getInstance(project)
    private val parser = CommitChecklistSpecParser()

    // ── Header labels ──────────────────────────────────────────────────────────
    private val titleLabel = JBLabel("Repo Setup Status").apply {
        font = JBFont.h2().asBold()
    }
    private val subtitleLabel = JBLabel("Configure your repository for commit checklist enforcement").apply {
        font = JBFont.label().biggerOn(1f)
    }

    // ── Current detected platform (updated in refreshStatus) ─────────────────
    private var currentPlatform: HostingPlatform = HostingPlatform.UNKNOWN

    // ── Status card value labels ───────────────────────────────────────────────
    private val platformBadge = JBLabel("--").apply {
        font = JBFont.label().asBold().biggerOn(1f)
    }
    private val specStatusLabel = JBLabel("--").apply {
        font = JBFont.label().asBold().biggerOn(1f)
    }
    private val templateStatusLabel = JBLabel("--").apply {
        font = JBFont.label().asBold().biggerOn(1f)
    }

    // ── Info field labels ──────────────────────────────────────────────────────
    private val repoRootValue = monospaceLabel()
    private val specPathValue = monospaceLabel()
    private val templatePathsValue = monospaceLabel()
    private val templateWarningValue = JBLabel("").apply {
        font = JBFont.label().asBold()
    }

    // ── Action buttons (reassigned in buildQuickActionsCard with RoundedButton) ─
    private var installSpecButton:      JButton = JButton("Install Spec")
    private var generateTemplatesButton: JButton = JButton("Generate Templates")
    private var openFilesButton:        JButton = JButton("Open Files")
    private var reloadButton:           JButton = JButton("Reload")

    // ── Cards ──────────────────────────────────────────────────────────────────
    private val platformCard  = roundedCard()
    private val specCard      = roundedCard()
    private val templateCard  = roundedCard()
    private val infoCard      = roundedCard()
    private val actionsCard   = roundedCard()

    // ──────────────────────────────────────────────────────────────────────────
    init {
        isOpaque = true

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(20, 24, 24, 24)
            alignmentX = LEFT_ALIGNMENT
        }

        content.add(buildHeader())
        content.add(vGap(16))
        content.add(buildStatusCards())
        content.add(vGap(16))
        content.add(buildRepositoryInfoCard())
        content.add(vGap(16))
        content.add(buildQuickActionsCard())

        val scroll = JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }
        add(scroll, BorderLayout.CENTER)

        project.messageBus.connect()
            .subscribe(VirtualFileManager.VFS_CHANGES, RepoSetupVfsListener())

        refreshStatus()
    }

    // ── paintComponent – page background ──────────────────────────────────────
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.color = p.pageBackground
        g2.fillRect(0, 0, width, height)
        super.paintComponent(g)
    }

    // ── Section builders ──────────────────────────────────────────────────────

    private fun buildHeader(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 48)
        titleLabel.apply {
            font = JBFont.h2().asBold()
            foreground = p.primaryText
            horizontalAlignment = javax.swing.SwingConstants.LEFT
            alignmentX = LEFT_ALIGNMENT
        }
        subtitleLabel.apply {
            font = JBFont.label().biggerOn(1f)
            foreground = p.mutedText
            horizontalAlignment = javax.swing.SwingConstants.LEFT
            alignmentX = LEFT_ALIGNMENT
        }
        add(titleLabel)
        add(vGap(2))
        add(subtitleLabel)
    }

    private fun buildStatusCards(): JComponent {
        val grid = JPanel(GridLayout(1, 3, 10, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 100)
            preferredSize = Dimension(900, 100)
        }

        platformCard.add(cardContent("Platform Detected", buildPlatformValuePanel()), BorderLayout.CENTER)
        specCard.add(cardContent("Spec Status", buildStatusValuePanel(specStatusLabel)), BorderLayout.CENTER)
        templateCard.add(cardContent("Template Status", buildStatusValuePanel(templateStatusLabel)), BorderLayout.CENTER)

        grid.add(platformCard)
        grid.add(specCard)
        grid.add(templateCard)
        return grid
    }

    /** Pill badge + optional platform icon */
    private fun buildPlatformValuePanel(): JComponent {
        val badgePill = object : JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(5, 10)
                platformBadge.isOpaque = false
                platformBadge.foreground = p.primaryText
                add(platformBadge)
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = p.badgeBackground
                g2.fillRoundRect(0, 0, width - 1, height - 1, height, height)
                super.paintComponent(g)
            }
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(PlatformIconLabel())
            add(badgePill)
        }
    }

    /** Status label with leading status dot/icon area */
    private fun buildStatusValuePanel(label: JBLabel): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(StatusIconLabel(label))
            add(label)
        }

    private fun buildRepositoryInfoCard(): JComponent {
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(14, 20, 16, 20)

            // Section title with folder icon – use BoxLayout row so it stretches correctly
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                maximumSize = Dimension(Int.MAX_VALUE, 24)
                alignmentX = LEFT_ALIGNMENT
                add(FolderIconLabel())
                add(javax.swing.Box.createRigidArea(Dimension(6, 0)))
                add(JBLabel("Repository Information").apply {
                    font = JBFont.label().asBold().biggerOn(2f)
                    foreground = p.primaryText
                })
            })
            add(vGap(12))
            add(infoField("Repo Root",      repoRootValue))
            add(vGap(8))
            add(infoField("Spec Path",      specPathValue))
            add(vGap(8))
            add(infoField("Template Paths", templatePathsValue))
            templateWarningValue.apply {
                foreground   = p.warningText
                alignmentX   = LEFT_ALIGNMENT
                maximumSize  = Dimension(Int.MAX_VALUE, 20)
            }
            if (templateWarningValue.text.isNotBlank()) {
                add(vGap(4))
                add(templateWarningValue)
            }
        }
        infoCard.apply {
            maximumSize  = Dimension(Int.MAX_VALUE, 230)
            preferredSize = Dimension(900, 230)
        }
        infoCard.add(body, BorderLayout.CENTER)
        return infoCard
    }

    private fun buildQuickActionsCard(): JComponent {
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(14, 20, 16, 20)

            // Title + subtitle – left-aligned
            add(JBLabel("Quick Actions").apply {
                font = JBFont.label().asBold().biggerOn(2f)
                foreground = p.primaryText
                alignmentX = LEFT_ALIGNMENT
            })
            add(vGap(2))
            add(JBLabel("Set up your repository with the required files").apply {
                font = JBFont.label().biggerOn(1f)
                foreground = p.mutedText
                alignmentX = LEFT_ALIGNMENT
            })
            add(vGap(12))

            // Equal-width rounded buttons filling the full row
            val btnRow = JPanel(GridLayout(1, 4, 10, 0)).apply {
                isOpaque = false
                maximumSize = Dimension(Int.MAX_VALUE, 38)
                preferredSize = Dimension(900, 38)
                alignmentX = LEFT_ALIGNMENT
            }

            installSpecButton       = RoundedButton("Install Spec",       ButtonStyle.PRIMARY)
            generateTemplatesButton = RoundedButton("Generate Templates",  ButtonStyle.SECONDARY)
            openFilesButton         = RoundedButton("Open Files",          ButtonStyle.GHOST)
            reloadButton            = RoundedButton("Reload",              ButtonStyle.GHOST)

            installSpecButton.addActionListener {
                CommitChecklistSpecInstallWorkflow.run(project) { refreshStatus() }
            }
            generateTemplatesButton.addActionListener { generateTemplates() }
            openFilesButton.addActionListener { openDetectedFiles() }
            reloadButton.addActionListener { refreshStatus() }

            btnRow.add(installSpecButton)
            btnRow.add(generateTemplatesButton)
            btnRow.add(openFilesButton)
            btnRow.add(reloadButton)
            add(btnRow)
        }
        actionsCard.apply {
            maximumSize = Dimension(Int.MAX_VALUE, 120)
            preferredSize = Dimension(900, 120)
        }
        actionsCard.add(body, BorderLayout.CENTER)
        return actionsCard
    }

    // ── Component helpers ──────────────────────────────────────────────────────

    private fun cardContent(title: String, value: JComponent): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(12, 16, 14, 16)
            add(JBLabel(title).apply {
                font = JBFont.label().asBold()
                foreground = p.mutedText
                alignmentX = LEFT_ALIGNMENT
            })
            add(vGap(10))
            value.alignmentX = LEFT_ALIGNMENT
            add(value)
        }

    private fun infoField(label: String, value: JBLabel): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 60)
            add(JBLabel(label).apply {
                font = JBFont.label().asBold()
                foreground = p.mutedText
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
            add(vGap(4))
            // Wrap value in a custom-painted rounded inset box
            val box = object : JPanel(BorderLayout()) {
                init {
                    isOpaque = false
                    border = JBUI.Borders.empty(7, 10)
                    add(value, BorderLayout.CENTER)
                }
                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = p.fieldBackground
                    g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
                    g2.color = p.cardBorder
                    g2.stroke = BasicStroke(1f)
                    g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
                    super.paintComponent(g)
                }
            }
            value.apply {
                isOpaque = false
                foreground = p.primaryText
            }
            box.alignmentX = LEFT_ALIGNMENT
            box.maximumSize = Dimension(Int.MAX_VALUE, 36)
            add(box)
        }



    private fun vGap(h: Int): JComponent = JPanel().apply {
        preferredSize = Dimension(1, h)
        minimumSize = Dimension(1, h)
        maximumSize = Dimension(Int.MAX_VALUE, h)
        isOpaque = false
    }

    private fun monospaceLabel() = JBLabel("--").apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size + 1)
    }

    // ── Rounded card factory ───────────────────────────────────────────────────

    private fun roundedCard(): JPanel = object : JBPanel<JBPanel<*>>(BorderLayout()) {
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // Fill
            g2.color = p.cardBackground
            g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
            // Border — single pixel, low contrast
            g2.color = p.cardBorder
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
            super.paintComponent(g)
        }
    }

    // ── Tiny icon components (drawn via Graphics2D – no external resources) ───

    /**
     * Platform icon drawn entirely via Graphics2D — no external resources needed.
     * Reads [currentPlatform] on every paint so a theme/status change is reflected
     * automatically on the next repaint.
     *
     *  GITHUB  → Octocat-inspired silhouette (circle head + cat ears + body)
     *  GITLAB  → Fox-head silhouette (angular ears + muzzle triangle)
     *  UNKNOWN → Question-mark inside a circle
     */
    private inner class PlatformIconLabel : JComponent() {
        init {
            preferredSize = Dimension(22, 22)
            minimumSize = preferredSize
            maximumSize = preferredSize
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            when (currentPlatform) {
                HostingPlatform.GITHUB  -> paintGitHub(g2)
                HostingPlatform.GITLAB  -> paintGitLab(g2)
                HostingPlatform.UNKNOWN -> paintUnknown(g2)
            }
        }

        /** GitHub: round head + cat ears + body, eyes cut out in card colour */
        private fun paintGitHub(g2: Graphics2D) {
            g2.color = p.primaryText
            // Ears (triangular bumps via filled ovals slightly above head)
            g2.fillOval(2, 0, 6, 6)
            g2.fillOval(14, 0, 6, 6)
            // Head (covers inner ear area)
            g2.fillOval(4, 2, 14, 13)
            // Body
            g2.fillRoundRect(4, 13, 14, 8, 4, 4)
            // Eye cutouts
            g2.color = p.cardBackground
            g2.fillOval(6, 6, 3, 3)
            g2.fillOval(13, 6, 3, 3)
        }

        /**
         * GitLab: angular fox-head.
         * Two pointy ears (filled triangles via polygons) + oval face + small
         * triangular muzzle at the bottom.
         */
        private fun paintGitLab(g2: Graphics2D) {
            g2.color = p.primaryText
            // Left ear
            val lx = intArrayOf(2, 6, 9)
            val ly = intArrayOf(12, 1, 8)
            g2.fillPolygon(lx, ly, 3)
            // Right ear
            val rx = intArrayOf(20, 16, 13)
            val ry = intArrayOf(12, 1, 8)
            g2.fillPolygon(rx, ry, 3)
            // Face oval
            g2.fillOval(5, 5, 12, 13)
            // Muzzle triangle (lighter – use mutedText so it reads as a lighter region)
            g2.color = p.mutedText
            val mx = intArrayOf(8, 14, 11)
            val my = intArrayOf(13, 13, 17)
            g2.fillPolygon(mx, my, 3)
        }

        /** Unknown: thin circle + "?" glyph */
        private fun paintUnknown(g2: Graphics2D) {
            g2.color = p.mutedText
            g2.stroke = BasicStroke(1.5f)
            g2.drawOval(1, 1, 19, 19)
            // Draw "?" using the label font, scaled to fit
            val font = JBFont.label().deriveFont(Font.BOLD, 12f)
            g2.font = font
            val fm = g2.getFontMetrics(font)
            val qStr = "?"
            val tx = (21 - fm.stringWidth(qStr)) / 2
            val ty = (21 + fm.ascent - fm.descent) / 2
            g2.drawString(qStr, tx.toFloat(), ty.toFloat())
        }
    }

    /** Status icon: green checkmark circle or red X circle */
    private inner class StatusIconLabel(private val target: JBLabel) : JComponent() {
        init {
            preferredSize = Dimension(18, 18)
            minimumSize = preferredSize
            maximumSize = preferredSize
            isOpaque = false
        }
        private val isOk get() = target.foreground == p.successText
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val color = if (isOk) p.successText else p.errorText
            // Circle border
            g2.color = color
            g2.stroke = BasicStroke(1.5f)
            g2.drawOval(1, 1, 15, 15)
            // Inner mark
            if (isOk) {
                g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(4, 9, 7, 13)
                g2.drawLine(7, 13, 13, 5)
            } else {
                g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(5, 5, 12, 12)
                g2.drawLine(12, 5, 5, 12)
            }
        }
    }

    /** Simple folder icon for the "Repository Information" section header */
    private inner class FolderIconLabel : JComponent() {
        init {
            preferredSize = Dimension(20, 20)
            minimumSize = preferredSize
            maximumSize = preferredSize
            isOpaque = false
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = p.primaryText
            g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            // Folder body
            g2.drawRoundRect(1, 6, 18, 12, 3, 3)
            // Folder tab
            g2.drawLine(1, 6, 1, 4)
            g2.drawLine(1, 4, 7, 4)
            g2.drawLine(7, 4, 9, 6)
        }
    }

    // ── Warning icon for spec path ─────────────────────────────────────────────

    private inner class WarningIconLabel : JComponent() {
        init {
            preferredSize = Dimension(18, 18)
            minimumSize = preferredSize
            maximumSize = preferredSize
            isOpaque = false
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = p.warningText
            g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            // Circle
            g2.drawOval(1, 1, 15, 15)
            // Exclamation
            g2.drawLine(9, 5, 9, 11)
            g2.fillOval(8, 13, 3, 3)
        }
    }

    // ── Status refresh ────────────────────────────────────────────────────────

    private fun refreshStatus() {
        // Re-apply colours (palette recalculated each call → theme switches handled)
        background = p.pageBackground
        titleLabel.foreground = p.primaryText
        subtitleLabel.foreground = p.mutedText
        platformBadge.background = p.badgeBackground
        platformBadge.foreground = p.primaryText
        templateWarningValue.foreground = p.warningText

        val status = resolveStatus()
        val hasSpec      = status.specPath != null
        val hasTemplates = status.templatePaths.isNotEmpty()

        // Platform badge + icon
        currentPlatform = status.platform
        platformBadge.text = status.platform.name

        // Spec status
        specStatusLabel.text       = if (hasSpec) "Found" else "Missing"
        specStatusLabel.foreground = if (hasSpec) p.successText else p.errorText

        // Template status
        templateStatusLabel.text       = if (hasTemplates) "Found" else "Missing"
        templateStatusLabel.foreground = if (hasTemplates) p.successText else p.errorText

        // Repo root field
        repoRootValue.text       = status.gitRoot?.toString() ?: "Not a Git repository"
        repoRootValue.background = p.fieldBackground
        repoRootValue.foreground = p.primaryText

        // Spec path field – warning colour when missing
        specPathValue.background = p.fieldBackground
        if (hasSpec) {
            specPathValue.text       = status.specPath.toString()
            specPathValue.foreground = p.primaryText
        } else {
            specPathValue.text       = "Not found"
            specPathValue.foreground = p.warningText
        }

        // Template paths field
        templatePathsValue.background = p.fieldBackground
        templatePathsValue.foreground = p.primaryText
        templatePathsValue.text =
            if (hasTemplates) status.templatePaths.joinToString(" | ")
            else status.expectedTemplatePathHint

        templateWarningValue.text      = status.templateWarning ?: ""
        templateWarningValue.isVisible = !status.templateWarning.isNullOrBlank()

        // Button states
        val hasGitRoot = status.gitRoot != null
        installSpecButton.isEnabled       = hasGitRoot
        generateTemplatesButton.isEnabled  = hasGitRoot && hasSpec
        openFilesButton.isEnabled         = hasSpec || hasTemplates
        // Repaint rounded buttons so hover/disabled shading re-evaluates
        listOf(installSpecButton, generateTemplatesButton, openFilesButton, reloadButton)
            .forEach { it.repaint() }

        repaint()
    }

    private fun resolveStatus(): RepoSetupStatus {
        val gitRoot = vcsService.resolveGitRepositoryRoot()
            ?: return RepoSetupStatus(
                gitRoot = null,
                platform = HostingPlatform.UNKNOWN,
                specPath = null,
                templatePaths = emptyList(),
                expectedTemplatePathHint = "--",
                templateWarning = null,
            )

        val platform = vcsService.detectHostingPlatform(gitRoot)
        val specPath = specService.refreshSpecPath()
        val (templates, hint, warning) = detectTemplateStatus(gitRoot, platform)
        return RepoSetupStatus(
            gitRoot = gitRoot,
            platform = platform,
            specPath = specPath,
            templatePaths = templates,
            expectedTemplatePathHint = hint,
            templateWarning = warning,
        )
    }

    private fun detectTemplateStatus(
        gitRoot: Path,
        platform: HostingPlatform,
    ): Triple<List<Path>, String, String?> = when (platform) {
        HostingPlatform.GITHUB -> {
            val d = GitHubPrTemplateDetector.detect(gitRoot)
            Triple(d.foundPath?.let { listOf(it) } ?: emptyList(), d.expectedPath.toString(), null)
        }
        HostingPlatform.GITLAB -> {
            val d = GitLabMrTemplateDetector.detect(gitRoot)
            val warn = if (d.isMissing) "No GitLab MR templates found in ${d.templatesDir}" else null
            Triple(d.foundTemplates, d.templatesDir.resolve("OpenReviewer.md").toString(), warn)
        }
        HostingPlatform.UNKNOWN -> Triple(emptyList(), "Unknown platform", null)
    }

    // ── Template generation ───────────────────────────────────────────────────

    private fun generateTemplates() {
        val status  = resolveStatus()
        val gitRoot = status.gitRoot ?: return
        val specPath = status.specPath
        if (specPath == null || !Files.isRegularFile(specPath)) {
            notifyError("Cannot generate templates because spec file is missing.")
            refreshStatus(); return
        }
        val spec = parser.parse(Files.readString(specPath), sourcePath = specPath)
        runCatching {
            val written = when (status.platform) {
                HostingPlatform.GITHUB  -> writeGithubTemplate(gitRoot, spec)
                HostingPlatform.GITLAB  -> writeGitlabTemplate(gitRoot, spec)
                HostingPlatform.UNKNOWN -> { notifyInfo("Platform unknown. Skipped."); false }
            }
            if (written) notifyInfo("Templates generated from commit checklist spec.")
        }.onFailure { notifyError("Failed to generate templates: ${it.message}") }
        refreshStatus()
    }

    private fun writeGithubTemplate(gitRoot: Path, spec: ChecklistSpec): Boolean {
        val path          = gitRoot.resolve(".github/pull_request_template.md")
        val newContent    = renderPrTemplate(spec)
        val existingContent = if (Files.isRegularFile(path)) Files.readString(path) else ""
        val diff = TemplateDiffBuilder.buildUnifiedDiff(path.toString(), existingContent, newContent)
        if (!TemplateDiffPreviewDialog(project, "Preview GitHub PR Template Changes", diff).showAndGet()) {
            notifyInfo("Template generation canceled."); return false
        }
        if (Files.exists(path)) {
            if (Messages.showYesNoDialog(project,
                    "Template already exists:\n${path.toAbsolutePath()}\n\nOverwrite it?",
                    "Overwrite GitHub Template", "Overwrite", "Cancel",
                    Messages.getQuestionIcon()) != Messages.YES) {
                notifyInfo("Template overwrite canceled."); return false
            }
        }
        Files.createDirectories(path.parent); Files.writeString(path, newContent); return true
    }

    private fun writeGitlabTemplate(gitRoot: Path, spec: ChecklistSpec): Boolean {
        val path = gitRoot.resolve(".gitlab/merge_request_templates/OpenReviewer.md")
        val newContent    = renderPrTemplate(spec)
        val existingContent = if (Files.isRegularFile(path)) Files.readString(path) else ""
        val diff = TemplateDiffBuilder.buildUnifiedDiff(path.toString(), existingContent, newContent)
        if (!TemplateDiffPreviewDialog(project, "Preview GitLab MR Template Changes", diff).showAndGet()) {
            notifyInfo("Template generation canceled."); return false
        }
        if (Files.exists(path)) {
            if (Messages.showYesNoDialog(project,
                    "Template already exists:\n${path.toAbsolutePath()}\n\nOverwrite it?",
                    "Overwrite GitLab Template", "Overwrite", "Cancel",
                    Messages.getQuestionIcon()) != Messages.YES) {
                notifyInfo("Template overwrite canceled."); return false
            }
        }
        Files.createDirectories(path.parent); Files.writeString(path, newContent); return true
    }

    private fun renderPrTemplate(spec: ChecklistSpec): String {
        val typeSection = if (spec.typeOptions.isEmpty()) "- [ ] N/A"
        else spec.typeOptions.joinToString("\n") { "- [ ] $it" }
        val checklistSection = if (spec.checklistItems.isEmpty()) "- [ ] N/A"
        else spec.checklistItems.joinToString("\n") { "- [ ] ${it.text}" }
        val description = spec.descriptionTemplate.ifBlank { "Describe what changed and why." }
        val guidance    = spec.reviewersGuidance.ifBlank { "Add notes for reviewers if needed." }
        return """
## Description
$description

## Type of Change
$typeSection

## Checklist
$checklistSection

## Reviewer's Guidance
$guidance
""".trimIndent() + "\n"
    }

    // ── File opening ──────────────────────────────────────────────────────────

    private fun openDetectedFiles() {
        val status = resolveStatus()
        status.specPath?.let { openPath(it) }
        status.templatePaths.forEach { openPath(it) }
    }

    private fun openPath(path: Path) {
        val vf = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(path.toAbsolutePath().toString()) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun notifyInfo(msg: String) =
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Open Reviewer Notifications")
            .createNotification(msg, NotificationType.INFORMATION).notify(project)

    private fun notifyError(msg: String) =
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Open Reviewer Notifications")
            .createNotification(msg, NotificationType.ERROR).notify(project)

    // ── VFS listener ──────────────────────────────────────────────────────────

    private inner class RepoSetupVfsListener : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
            val relevant = events.any { e ->
                e.path.endsWith("COMMIT_CHECKLIST.md") ||
                        e.path.contains("/.github/") ||
                        e.path.contains("/.gitlab/")
            }
            if (!relevant) return
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) refreshStatus()
            }
        }
    }

    // ── Data models ───────────────────────────────────────────────────────────

    private data class RepoSetupStatus(
        val gitRoot: Path?,
        val platform: HostingPlatform,
        val specPath: Path?,
        val templatePaths: List<Path>,
        val expectedTemplatePathHint: String,
        val templateWarning: String?,
    )

    // ── Palette (recomputed each access so it follows IDE theme changes) ───────

    private data class Palette(
        val pageBackground:      Color,
        val cardBackground:      Color,
        val cardBorder:          Color,
        val fieldBackground:     Color,
        val badgeBackground:     Color,
        val primaryText:         Color,
        val mutedText:           Color,
        val successText:         Color,
        val errorText:           Color,
        val warningText:         Color,
        val primaryButtonBg:     Color,
        val primaryButtonFg:     Color,
        val secondaryButtonBg:   Color,
    )

    /**
     * Colours expressed as [JBColor] (light / dark pairs) so they automatically
     * adapt when the IDE theme is switched without any extra listener wiring.
     *
     * Light values match the screenshot; dark values are carefully chosen
     * complements that keep contrast ratios WCAG-AA compliant.
     */
    private val p: Palette
        get() = Palette(
            // Dark: deep near-black page, cards are a single step lighter with a
            // barely-visible border — matching the reference screenshot style.
            pageBackground    = JBColor(Color(0xF1F3F5), Color(0x1E1F22)),
            cardBackground    = JBColor(Color(0xFFFFFF), Color(0x2B2D30)),
            cardBorder        = JBColor(Color(0xD0D5DD), Color(0x3C3F41)),
            // Fields sit darker than the card (inset look)
            fieldBackground   = JBColor(Color(0xF1F3F5), Color(0x1E1F22)),
            badgeBackground   = JBColor(Color(0xE4E7EC), Color(0x393B40)),
            primaryText       = UIUtil.getLabelForeground(),
            mutedText         = JBColor(Color(0x667085), Color(0x888D94)),
            successText       = JBColor(Color(0x0E7A6E), Color(0x4CAF88)),
            errorText         = JBColor(Color(0xC62828), Color(0xFF6B6B)),
            warningText       = JBColor(Color(0xB45309), Color(0xE8A838)),
            // Primary button: high contrast in both themes
            primaryButtonBg   = JBColor(Color(0x1A1A1A), Color(0xEEEEEE)),
            primaryButtonFg   = JBColor(Color(0xFFFFFF), Color(0x1A1A1A)),
            // Secondary: subtle tonal
            secondaryButtonBg = JBColor(Color(0xE4E7EC), Color(0x393B40)),
        )

    // ── RoundedButton ─────────────────────────────────────────────────────────

    /**
     * A [JButton] with fully-rounded corners (pill shape), no visible border,
     * and a subtle hover darkening — all drawn via [paintComponent] so it
     * respects the palette and works in both light and dark IDE themes.
     */
    enum class ButtonStyle { PRIMARY, SECONDARY, GHOST }

    inner class RoundedButton(text: String, private val style: ButtonStyle) : JButton(text) {

        private var hovered = false

        init {
            isOpaque = false
            isContentAreaFilled = false
            isFocusPainted = false
            isBorderPainted = false
            border = JBUI.Borders.empty(10, 20)
            font = JBFont.label().asBold().biggerOn(1f)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent) { hovered = true;  repaint() }
                override fun mouseExited(e: java.awt.event.MouseEvent)  { hovered = false; repaint() }
            })
        }

        private fun baseBg(): Color = when (style) {
            ButtonStyle.PRIMARY   -> p.primaryButtonBg
            ButtonStyle.SECONDARY -> p.secondaryButtonBg
            ButtonStyle.GHOST     -> p.cardBackground
        }

        private fun baseFg(): Color = when (style) {
            ButtonStyle.PRIMARY -> p.primaryButtonFg
            else                -> p.primaryText
        }

        /** Returns true when the current IDE theme has a dark background. */
        private fun isDarkTheme(): Boolean {
            val bg = p.pageBackground
            // Perceived luminance (ITU-R BT.709)
            val luminance = 0.2126 * bg.red + 0.7152 * bg.green + 0.0722 * bg.blue
            return luminance < 128
        }

        /** Darken or lighten `c` by `amount` depending on IDE theme brightness */
        private fun shade(c: Color, amount: Int): Color {
            val delta = if (isDarkTheme()) amount else -amount
            return Color(
                (c.red   + delta).coerceIn(0, 255),
                (c.green + delta).coerceIn(0, 255),
                (c.blue  + delta).coerceIn(0, 255),
                c.alpha,
            )
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val bg = if (hovered && isEnabled) shade(baseBg(), 18) else baseBg()
            g2.color = bg
            // Fully-rounded pill: arc = height so corners become perfect semicircles
            g2.fillRoundRect(0, 0, width, height, height, height)

            foreground = if (isEnabled) baseFg() else p.mutedText
            super.paintComponent(g)
        }
    }
}