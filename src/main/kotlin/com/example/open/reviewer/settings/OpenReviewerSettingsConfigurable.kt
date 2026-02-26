package com.example.open.reviewer.settings

import com.example.open.reviewer.ai.AiClientService
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.border.Border

class OpenReviewerSettingsConfigurable : SearchableConfigurable {
    private val settingsService = OpenReviewerSettingsService.getInstance()
    private val aiClient = ApplicationManager.getApplication().getService(AiClientService::class.java)

    private var rootPanel: JPanel? = null

    private lateinit var providerCombo: JComboBox<AiProvider>
    private lateinit var apiKeyField: JPasswordField
    private lateinit var apiVisibilityButton: JToggleButton
    private lateinit var modelCombo: JComboBox<String>
    private lateinit var loadModelsButton: JButton
    private lateinit var endpointField: javax.swing.JTextField
    private lateinit var includeCodeContextCheckbox: JCheckBox
    private lateinit var codeContextInfoLabel: JBLabel
    private lateinit var endpointLabel: JBLabel
    private lateinit var endpointRow: JPanel

    private lateinit var testConnectionButton: JButton

    private lateinit var connectionStatePanel: JPanel
    private lateinit var connectionStateIcon: JBLabel
    private lateinit var connectionStateTitle: JBLabel
    private lateinit var connectionStateDetails: JBLabel

    private val defaultEchoChar = '\u2022'

    override fun getId(): String = "openReviewer.settings"

    override fun getDisplayName(): String = "Open Reviewer"

    override fun createComponent(): JComponent {
        providerCombo =
            JComboBox(AiProvider.entries.toTypedArray()).apply {
                preferredSize = Dimension(380, 34)
            }

        apiKeyField =
            JPasswordField().apply {
                echoChar = defaultEchoChar
                preferredSize = Dimension(380, 34)
            }
        apiVisibilityButton =
            JToggleButton("Show").apply {
                addActionListener {
                    val visible = isSelected
                    text = if (visible) "Hide" else "Show"
                    apiKeyField.echoChar = if (visible) 0.toChar() else defaultEchoChar
                }
            }

        modelCombo =
            JComboBox(DefaultComboBoxModel<String>()).apply {
                isEditable = true
                preferredSize = Dimension(300, 34)
            }
        loadModelsButton =
            JButton("Load Models").apply {
                preferredSize = Dimension(130, 34)
                addActionListener { runModelLoad() }
            }
        endpointField =
            com.intellij.ui.components.JBTextField().apply {
                preferredSize = Dimension(380, 34)
            }
        includeCodeContextCheckbox =
            JCheckBox("Include code context in AI requests (bounded snippets)").apply {
                isOpaque = false
            }
        codeContextInfoLabel =
            JBLabel(
                "<html><body style='width: 360px;'>" +
                    "When enabled, Open Reviewer includes full detected entry-point files " +
                    "(for example main.dart/main_prod.dart), capped to ~40k total characters. " +
                    "It does not send your entire project." +
                    "</body></html>",
            ).apply {
                foreground = JBColor(Color(0x5C6470), Color(0xB7C0CC))
                border = JBUI.Borders.emptyLeft(4)
                isVisible = false
            }
        includeCodeContextCheckbox.addActionListener {
            updateCodeContextInfoVisibility()
        }

        testConnectionButton =
            JButton("Test Connection").apply {
                preferredSize = Dimension(380, 38)
            }
        testConnectionButton.addActionListener { runConnectionTest() }

        val form = JBPanel<JBPanel<*>>(GridBagLayout())
        form.border = JBUI.Borders.empty(16)

        addRow(form, 0, "Provider:", providerCombo)
        addRow(form, 1, "API key:", buildPasswordRow())
        addRow(form, 2, "Model:", buildModelRow())

        endpointLabel = JBLabel("Endpoint:")
        endpointRow =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(endpointField, BorderLayout.CENTER)
            }
        addLabeledRow(form, 3, endpointLabel, endpointRow)
        addRow(form, 4, "", includeCodeContextCheckbox)
        addRow(form, 5, "", codeContextInfoLabel)

        addRow(form, 6, "", testConnectionButton)

        connectionStatePanel = buildConnectionStatePanel()
        addRow(form, 7, "", connectionStatePanel)

        addRow(form, 8, "", buildNotePanel())

        providerCombo.addActionListener {
            updateEndpointVisibility()
            clearLoadedModelsPreservingSelection()
        }

        val spacer = JPanel().apply { isOpaque = false }
        addFill(form, 9, spacer)

        rootPanel = form
        reset()
        updateConnectionState(ConnectionUiState.IDLE)
        return form
    }

    override fun isModified(): Boolean {
        val state = settingsService.state
        return providerCombo.selectedItem != state.provider ||
            String(apiKeyField.password) != state.apiKey ||
            currentModelValue() != state.model ||
            endpointField.text != state.endpoint ||
            includeCodeContextCheckbox.isSelected != state.includeCodeContext
    }

    override fun apply() {
        val state = settingsService.state
        state.provider = providerCombo.selectedItem as? AiProvider ?: AiProvider.OPENAI
        state.apiKey = String(apiKeyField.password)
        state.model = currentModelValue()
        state.endpoint = endpointField.text.trim()
        state.includeCodeContext = includeCodeContextCheckbox.isSelected
    }

    override fun reset() {
        val state = settingsService.state
        providerCombo.selectedItem = state.provider
        apiKeyField.text = state.apiKey
        clearLoadedModelsPreservingSelection()
        setModelSelection(state.model)
        endpointField.text = state.endpoint
        includeCodeContextCheckbox.isSelected = state.includeCodeContext

        apiVisibilityButton.isSelected = false
        apiVisibilityButton.text = "Show"
        apiKeyField.echoChar = defaultEchoChar

        updateEndpointVisibility()
        updateCodeContextInfoVisibility()
    }

    override fun disposeUIResources() {
        rootPanel = null
    }

    private fun buildPasswordRow(): JComponent {
        val row = JPanel(BorderLayout(8, 0))
        row.isOpaque = false
        apiVisibilityButton.preferredSize = Dimension(90, 34)
        row.add(apiKeyField, BorderLayout.CENTER)
        row.add(apiVisibilityButton, BorderLayout.EAST)
        return row
    }

    private fun buildModelRow(): JComponent {
        val row = JPanel(BorderLayout(8, 0))
        row.isOpaque = false
        row.add(modelCombo, BorderLayout.CENTER)
        row.add(loadModelsButton, BorderLayout.EAST)
        return row
    }

    private fun buildConnectionStatePanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(380, 86)
        panel.isOpaque = true
        panel.border = stateCardBorder(JBColor.border(), JBColor.border())

        connectionStateIcon = JBLabel()
        connectionStateIcon.verticalAlignment = SwingConstants.CENTER
        connectionStateIcon.border = JBUI.Borders.emptyRight(8)

        connectionStateTitle =
            JBLabel().apply {
                font = JBFont.label().asBold()
            }

        connectionStateDetails =
            JBLabel().apply {
                foreground = JBColor.GRAY
            }

        val textPanel = JPanel(GridBagLayout())
        textPanel.isOpaque = false

        val titleConstraints =
            GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = Insets(0, 0, 4, 0)
            }
        textPanel.add(connectionStateTitle, titleConstraints)

        val detailsConstraints =
            GridBagConstraints().apply {
                gridx = 0
                gridy = 1
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
            }
        textPanel.add(connectionStateDetails, detailsConstraints)

        panel.add(connectionStateIcon, BorderLayout.WEST)
        panel.add(textPanel, BorderLayout.CENTER)

        return panel
    }

    private fun buildNotePanel(): JPanel {
        val notePanel = JPanel(GridBagLayout())
        notePanel.border =
            BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(10),
            )

        val line1 = JBLabel("Note: Your API key is stored locally and never shared.")
        val line2 = JBLabel("Open Reviewer uses AI to analyze your code and provide performance suggestions.")

        val c1 =
            GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                weightx = 1.0
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 6, 0)
            }
        val c2 =
            GridBagConstraints().apply {
                gridx = 0
                gridy = 1
                weightx = 1.0
                anchor = GridBagConstraints.WEST
            }

        notePanel.add(line1, c1)
        notePanel.add(line2, c2)

        return notePanel
    }

    private fun runConnectionTest() {
        val draftSettings =
            OpenReviewerSettingsState(
                provider = providerCombo.selectedItem as? AiProvider ?: AiProvider.OPENAI,
                apiKey = String(apiKeyField.password),
                model = currentModelValue(),
                endpoint = endpointField.text.trim(),
            )

        updateConnectionState(ConnectionUiState.LOADING)
        testConnectionButton.isEnabled = false

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(null, "Testing Open Reviewer Connection", false) {
                private var success = false
                private var message = ""

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val result = aiClient.ping(draftSettings)
                    success = result.success
                    message = result.message
                }

                override fun onSuccess() {
                    testConnectionButton.isEnabled = true
                    if (success) {
                        updateConnectionState(ConnectionUiState.SUCCESS, message)
                    } else {
                        updateConnectionState(ConnectionUiState.FAILURE, message)
                    }

                    val type = if (success) NotificationType.INFORMATION else NotificationType.ERROR
                    val title = if (success) "Open Reviewer connection successful" else "Open Reviewer connection failed"
                    notify(title, message, type)
                }

                override fun onThrowable(error: Throwable) {
                    testConnectionButton.isEnabled = true
                    val failureMessage = error.message ?: "Unexpected error while testing connection."
                    updateConnectionState(ConnectionUiState.FAILURE, failureMessage)
                    notify("Open Reviewer connection failed", failureMessage, NotificationType.ERROR)
                }
            },
        )
    }

    private fun updateConnectionState(
        state: ConnectionUiState,
        details: String? = null,
    ) {
        connectionStatePanel.isVisible = state != ConnectionUiState.IDLE
        if (state == ConnectionUiState.IDLE) return

        when (state) {
            ConnectionUiState.LOADING -> {
                val accent = JBColor(Color(0x4C78C2), Color(0x6AA4FF))
                applyStateCardStyle(
                    accent = accent,
                    titleColor = JBColor.foreground(),
                    icon = AllIcons.Actions.Refresh,
                )
                connectionStateTitle.text = "Testing connection..."
                connectionStateDetails.text = details ?: "Validating provider credentials and endpoint..."
            }

            ConnectionUiState.SUCCESS -> {
                val accent = JBColor(Color(0x2F8F5B), Color(0x73D59C))
                applyStateCardStyle(
                    accent = accent,
                    titleColor = JBColor.foreground(),
                    icon = AllIcons.General.InspectionsOK,
                )
                connectionStateTitle.text = "Connection successful"
                connectionStateDetails.text = details ?: "Successfully connected to selected provider."
            }

            ConnectionUiState.FAILURE -> {
                val accent = JBColor(Color(0xC74848), Color(0xFF8F8F))
                applyStateCardStyle(
                    accent = accent,
                    titleColor = JBColor.foreground(),
                    icon = AllIcons.General.Error,
                )
                connectionStateTitle.text = "Connection failed"
                connectionStateDetails.text = details ?: "Failed to connect. Check API key and network configuration."
            }

            ConnectionUiState.IDLE -> Unit
        }

        connectionStatePanel.revalidate()
        connectionStatePanel.repaint()
    }

    private fun applyStateCardStyle(
        accent: JBColor,
        titleColor: Color,
        icon: javax.swing.Icon,
    ) {
        connectionStatePanel.background = JBColor(Color(0xF7F8FA), Color(0x31343A))
        connectionStatePanel.border = stateCardBorder(accent, JBColor(Color(0xD7DCE5), Color(0x4A4F59)))
        connectionStateIcon.icon = icon
        connectionStateIcon.foreground = accent
        connectionStateTitle.foreground = titleColor
        connectionStateDetails.foreground = JBColor(Color(0x5C6470), Color(0xB7C0CC))
    }

    private fun stateCardBorder(
        leftAccent: Color,
        outer: Color,
    ): Border {
        return JBUI.Borders.compound(
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 3, 1, 1, leftAccent),
                BorderFactory.createLineBorder(outer),
            ),
            JBUI.Borders.empty(10),
        )!!
    }

    private fun updateEndpointVisibility() {
        val isCustom = (providerCombo.selectedItem as? AiProvider) == AiProvider.CUSTOM
        endpointLabel.isVisible = isCustom
        endpointRow.isVisible = isCustom
        endpointRow.revalidate()
        endpointRow.repaint()
    }

    private fun updateCodeContextInfoVisibility() {
        codeContextInfoLabel.isVisible = includeCodeContextCheckbox.isSelected
        codeContextInfoLabel.revalidate()
        codeContextInfoLabel.repaint()
    }

    private fun runModelLoad() {
        val draftSettings =
            OpenReviewerSettingsState(
                provider = providerCombo.selectedItem as? AiProvider ?: AiProvider.OPENAI,
                apiKey = String(apiKeyField.password),
                model = currentModelValue(),
                endpoint = endpointField.text.trim(),
            )
        if (draftSettings.apiKey.isBlank()) {
            notify("Load models failed", "API key is required before loading models.", NotificationType.WARNING)
            return
        }
        if (draftSettings.provider == AiProvider.CUSTOM && draftSettings.endpoint.isBlank()) {
            notify("Load models failed", "Endpoint is required for CUSTOM provider.", NotificationType.WARNING)
            return
        }

        loadModelsButton.isEnabled = false
        loadModelsButton.text = "Loading..."
        val previous = currentModelValue()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(null, "Loading Open Reviewer Models", false) {
                private var loadedModels: List<String> = emptyList()
                private var errorMessage: String? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    runCatching {
                        aiClient.listModels(draftSettings)
                    }.onSuccess {
                        loadedModels = it
                    }.onFailure {
                        errorMessage = it.message ?: "Failed to load models."
                    }
                }

                override fun onSuccess() {
                    ApplicationManager.getApplication().invokeLater {
                        loadModelsButton.isEnabled = true
                        loadModelsButton.text = "Load Models"

                        if (errorMessage != null) {
                            notify("Load models failed", errorMessage!!, NotificationType.ERROR)
                            return@invokeLater
                        }
                        if (loadedModels.isEmpty()) {
                            notify("No models returned", "Provider returned no model IDs for this account.", NotificationType.WARNING)
                            return@invokeLater
                        }

                        setModelOptions(loadedModels, previous)
                        notify("Models loaded", "Loaded ${loadedModels.size} models.", NotificationType.INFORMATION)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        loadModelsButton.isEnabled = true
                        loadModelsButton.text = "Load Models"
                        notify("Load models failed", error.message ?: "Unexpected error while loading models.", NotificationType.ERROR)
                    }
                }
            },
        )
    }

    private fun setModelOptions(
        models: List<String>,
        preferred: String,
    ) {
        val editorValue = preferred.ifBlank { currentModelValue() }
        val model = modelCombo.model as DefaultComboBoxModel<String>
        model.removeAllElements()
        models.forEach { model.addElement(it) }

        val selected =
            when {
                editorValue.isNotBlank() && models.contains(editorValue) -> editorValue
                editorValue.isNotBlank() -> editorValue
                models.isNotEmpty() -> models.first()
                else -> ""
            }
        setModelSelection(selected)
    }

    private fun clearLoadedModelsPreservingSelection() {
        val current = currentModelValue()
        val model = modelCombo.model as DefaultComboBoxModel<String>
        model.removeAllElements()
        setModelSelection(current)
    }

    private fun setModelSelection(value: String) {
        modelCombo.selectedItem = value
        modelCombo.editor.item = value
    }

    private fun currentModelValue(): String {
        val item = modelCombo.editor.item?.toString()?.trim().orEmpty()
        return if (item.isNotBlank()) item else (modelCombo.selectedItem?.toString()?.trim().orEmpty())
    }

    private fun addRow(
        container: JPanel,
        row: Int,
        labelText: String,
        component: JComponent,
    ) {
        val label = JBLabel(labelText)
        addLabeledRow(container, row, label, component)
    }

    private fun addLabeledRow(
        container: JPanel,
        row: Int,
        label: JBLabel,
        component: JComponent,
    ) {
        val labelConstraints =
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(6, 0, 6, 12)
            }
        container.add(label, labelConstraints)

        val fieldConstraints =
            GridBagConstraints().apply {
                gridx = 1
                gridy = row
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                insets = Insets(6, 0, 6, 0)
            }
        container.add(component, fieldConstraints)
    }

    private fun addFill(
        container: JPanel,
        row: Int,
        component: JComponent,
    ) {
        val constraints =
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                gridwidth = 2
                weighty = 1.0
                fill = GridBagConstraints.BOTH
            }
        container.add(component, constraints)
    }

    private fun notify(
        title: String,
        content: String,
        type: NotificationType,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Open Reviewer Notifications")
            .createNotification(title, content, type)
            .notify(null)
    }
}

private enum class ConnectionUiState {
    IDLE,
    LOADING,
    SUCCESS,
    FAILURE,
}
