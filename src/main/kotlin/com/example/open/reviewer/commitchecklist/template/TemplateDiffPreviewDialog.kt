package com.example.open.reviewer.commitchecklist.template

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

class TemplateDiffPreviewDialog(
    project: Project,
    title: String,
    diffText: String,
) : DialogWrapper(project) {
    private val previewArea =
        JBTextArea().apply {
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            font = Font("JetBrains Mono", Font.PLAIN, 12)
            text = diffText
            caretPosition = 0
            border = JBUI.Borders.empty(8)
        }

    init {
        this.title = title
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        val scrollPane = JBScrollPane(previewArea)
        scrollPane.preferredSize = Dimension(900, 520)
        root.add(scrollPane, BorderLayout.CENTER)
        return root
    }

    override fun getOKAction() = super.getOKAction().apply { putValue(Action.NAME, "Write File") }
}
