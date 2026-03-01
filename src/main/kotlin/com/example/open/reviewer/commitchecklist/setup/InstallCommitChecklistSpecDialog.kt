package com.example.open.reviewer.commitchecklist.setup

import com.example.open.reviewer.commitchecklist.spec.CommitChecklistSpecRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.nio.file.Path
import javax.swing.ComboBoxModel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class InstallCommitChecklistSpecDialog(
    project: Project,
    private val locationOptions: List<Path>,
) : DialogWrapper(project) {
    private val renderer = CommitChecklistSpecRenderer()

    private val locationModel: ComboBoxModel<Path> = DefaultComboBoxModel(locationOptions.toTypedArray())
    private val locationCombo = JComboBox(locationModel)
    private val presetModel: ComboBoxModel<CommitChecklistTemplatePreset> =
        DefaultComboBoxModel(CommitChecklistTemplatePreset.entries.toTypedArray())
    private val presetCombo = JComboBox(presetModel)

    private val previewArea =
        JBTextArea().apply {
            isEditable = false
            font = Font("JetBrains Mono", Font.PLAIN, 12)
            lineWrap = true
            wrapStyleWord = false
            border = JBUI.Borders.empty(8)
        }

    init {
        title = "Install Commit Checklist Spec"
        init()
        updatePreview()

        locationCombo.addActionListener { updatePreview() }
        presetCombo.addActionListener { updatePreview() }
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 10))

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        controls.add(JBLabel("Location"))
        controls.add(locationCombo)
        controls.add(JBLabel("Template preset"))
        controls.add(presetCombo)

        val previewScroll = JBScrollPane(previewArea)
        previewScroll.preferredSize = Dimension(820, 420)
        previewScroll.border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())

        root.add(controls, BorderLayout.NORTH)
        root.add(previewScroll, BorderLayout.CENTER)
        return root
    }

    fun installRequest(): CommitChecklistInstallRequest? {
        val path = locationCombo.selectedItem as? Path ?: return null
        val preset = presetCombo.selectedItem as? CommitChecklistTemplatePreset ?: return null
        return CommitChecklistInstallRequest(targetPath = path, preset = preset)
    }

    private fun updatePreview() {
        val request = installRequest() ?: return
        val spec = CommitChecklistTemplateFactory.createSpec(request.preset, request.targetPath)
        previewArea.text = renderer.render(spec)
        previewArea.caretPosition = 0
    }
}

