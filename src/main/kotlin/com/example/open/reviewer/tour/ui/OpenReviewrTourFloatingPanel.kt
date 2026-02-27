package com.example.open.reviewer.tour.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea

class OpenReviewrTourFloatingPanel(
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onExit: () -> Unit,
) {
    private var popup: JBPopup? = null

    private val titleLabel = JBLabel("Code Tour").apply { font = JBFont.label().asBold() }
    private val counterLabel = JBLabel("1 / 1").apply { foreground = UIUtil.getContextHelpForeground() }
    private val explanationArea =
        JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            font = JBFont.label().asPlain()
        }
    private val previousButton = JButton("Previous")
    private val nextButton = JButton("Next")
    private val exitButton = JButton("Exit")

    private val content =
        JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(10)
            val top = JPanel(BorderLayout()).apply { isOpaque = false }
            top.add(titleLabel, BorderLayout.WEST)
            top.add(counterLabel, BorderLayout.EAST)

            val controls = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
            controls.add(previousButton)
            controls.add(nextButton)
            controls.add(exitButton)

            add(top, BorderLayout.NORTH)
            add(explanationArea, BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
        }

    init {
        previousButton.addActionListener { onPrevious() }
        nextButton.addActionListener { onNext() }
        exitButton.addActionListener { onExit() }
    }

    fun showOrUpdate(
        editor: Editor,
        tourTitle: String,
        stepNumber: Int,
        totalSteps: Int,
        explanation: String,
        hasPrevious: Boolean,
        hasNext: Boolean,
    ) {
        titleLabel.text = tourTitle
        counterLabel.text = "$stepNumber / $totalSteps"
        explanationArea.text = explanation
        previousButton.isEnabled = hasPrevious
        nextButton.isEnabled = hasNext

        val activePopup = popup
        if (activePopup == null || activePopup.isDisposed) {
            popup =
                JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(content, null)
                    .setFocusable(false)
                    .setRequestFocus(false)
                    .setResizable(false)
                    .setMovable(true)
                    .setCancelOnClickOutside(false)
                    .setCancelOnOtherWindowOpen(false)
                    .createPopup()
            popup?.showInBestPositionFor(editor)
        }
        content.revalidate()
        content.repaint()
    }

    fun close() {
        popup?.cancel()
        popup = null
    }
}
