package com.example.open.reviewer.commitchecklist.ui

import com.example.open.reviewer.commitchecklist.spec.ChecklistItem
import com.example.open.reviewer.commitchecklist.spec.ChecklistSpec
import com.example.open.reviewer.commitchecklist.validation.CommitChecklistValidationConfig
import com.example.open.reviewer.commitchecklist.validation.CommitChecklistValidationEngine
import com.example.open.reviewer.commitchecklist.validation.CommitChecklistValidationField
import com.example.open.reviewer.commitchecklist.validation.CommitChecklistValidationInput
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

data class CommitChecklistDialogResult(
    val description: String,
    val typeOfChange: String?,
    val checklistItems: List<ChecklistItem>,
    val reviewersGuidance: String,
)

class CommitChecklistDialog(
    project: Project,
    private val spec: ChecklistSpec,
    initialDescription: String = spec.descriptionTemplate,
    initialTypeOfChange: String? = null,
    validationConfig: CommitChecklistValidationConfig = CommitChecklistValidationConfig(),
) : DialogWrapper(project) {
    private val validationEngine = CommitChecklistValidationEngine(validationConfig)
    private val descriptionArea =
        JBTextArea(initialDescription, 5, 80).apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(8)
        }
    private val typeCombo = ComboBox<String>()
    private val guidanceArea =
        JBTextArea(spec.reviewersGuidance, 5, 80).apply {
            lineWrap = true
            wrapStyleWord = true
            isEditable = false
            isFocusable = true
            border = JBUI.Borders.empty(8)
            background = JBColor(UIUtil.getPanelBackground(), UIUtil.getPanelBackground())
        }
    private val checklistBoxes: List<JBCheckBox> =
        spec.checklistItems.map { item ->
            JBCheckBox(item.text, item.checked).apply {
                border = JBUI.Borders.empty(2, 0)
            }
        }

    init {
        title = "Commit Checklist"
        setOKButtonText("Confirm")
        initTypeOptions(initialTypeOfChange)
        init()
        bindEnterToConfirm(descriptionArea)
    }

    fun showAndGetResult(): CommitChecklistDialogResult? {
        if (!showAndGet()) return null
        return CommitChecklistDialogResult(
            description = descriptionArea.text.trim(),
            typeOfChange = selectedTypeOfChange(),
            checklistItems =
                checklistBoxes.map { box ->
                    ChecklistItem(text = box.text, checked = box.isSelected)
                },
            reviewersGuidance = spec.reviewersGuidance.trim(),
        )
    }

    override fun createCenterPanel(): JComponent {
        val root =
            JPanel(GridBagLayout()).apply {
                border = JBUI.Borders.empty(8)
            }

        var row = 0
        fun constraints(top: Int = 0) =
            GridBagConstraints().apply {
                gridx = 0
                gridy = row++
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(top, 0, 0, 0)
            }

        root.add(sectionTitle("Description"), constraints())
        root.add(scroll(descriptionArea, height = 120), constraints(6))

        root.add(sectionTitle("Type of Change"), constraints(10))
        root.add(typeCombo, constraints(6))

        root.add(sectionTitle("Checklist"), constraints(10))
        root.add(checklistPanel(), constraints(6))

        root.add(sectionTitle("Reviewer’s Guidance"), constraints(10))
        root.add(scroll(guidanceArea, height = 110), constraints(6))

        return root
    }

    override fun getPreferredFocusedComponent(): JComponent = descriptionArea

    override fun doValidateAll(): MutableList<ValidationInfo> {
        val input =
            CommitChecklistValidationInput(
                description = descriptionArea.text.trim(),
                typeOfChange = selectedTypeOfChange(),
                checklistItemStates = checklistBoxes.map { it.isSelected },
            )
        val validation = validationEngine.validate(input)
        if (validation.isValid) return mutableListOf()

        val validationInfos =
            validation.errors.mapNotNull { error ->
                when (error.field) {
                    CommitChecklistValidationField.DESCRIPTION -> ValidationInfo(error.message, descriptionArea)
                    CommitChecklistValidationField.TYPE_OF_CHANGE -> ValidationInfo(error.message, typeCombo)
                    CommitChecklistValidationField.CHECKLIST_ITEM -> {
                        val index = error.checklistItemIndex ?: return@mapNotNull null
                        val target = checklistBoxes.getOrNull(index) ?: return@mapNotNull null
                        ValidationInfo(error.message, target)
                    }
                }
            }
        return validationInfos.toMutableList()
    }

    private fun initTypeOptions(initialTypeOfChange: String?) {
        val typeOptions = spec.typeOptions.ifEmpty { listOf("Unspecified") }
        typeOptions.forEach { typeCombo.addItem(it) }
        val selected = initialTypeOfChange?.takeIf { it in typeOptions } ?: typeOptions.first()
        typeCombo.selectedItem = selected
    }

    private fun selectedTypeOfChange(): String? {
        return (typeCombo.selectedItem as? String)?.trim()?.ifBlank { null }
    }

    private fun sectionTitle(text: String): JBLabel {
        return JBLabel(text).apply {
            font = JBFont.label().asBold()
        }
    }

    private fun checklistPanel(): JComponent {
        val panel =
            JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(4, 0)
                isOpaque = false
            }
        checklistBoxes.forEach { panel.add(it) }
        return panel
    }

    private fun scroll(
        content: JComponent,
        height: Int,
    ): JComponent {
        return JBScrollPane(content).apply {
            preferredSize = Dimension(840, height)
            border = JBUI.Borders.customLine(UIUtil.getBoundsColor())
        }
    }

    private fun bindEnterToConfirm(component: JComponent) {
        val inputMap = component.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = component.actionMap

        val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        val shiftEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK)

        // Enter confirms dialog; Shift+Enter keeps newline behavior in textarea.
        inputMap.put(enter, "openreviewer.commitChecklist.confirm")
        inputMap.put(shiftEnter, "openreviewer.commitChecklist.newline")

        actionMap.put(
            "openreviewer.commitChecklist.confirm",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    doOKAction()
                }
            },
        )
        actionMap.put(
            "openreviewer.commitChecklist.newline",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    descriptionArea.append("\n")
                }
            },
        )
    }
}
