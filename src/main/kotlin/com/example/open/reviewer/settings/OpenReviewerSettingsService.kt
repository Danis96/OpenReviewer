package com.example.open.reviewer.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "OpenReviewerSettings", storages = [Storage("open-reviewer.xml")])
class OpenReviewerSettingsService : PersistentStateComponent<OpenReviewerSettingsState> {
    private var state: OpenReviewerSettingsState = OpenReviewerSettingsState()

    override fun getState(): OpenReviewerSettingsState = state

    override fun loadState(state: OpenReviewerSettingsState) {
        this.state = state
    }

    companion object {
        fun getInstance(): OpenReviewerSettingsService {
            return ApplicationManager.getApplication().getService(OpenReviewerSettingsService::class.java)
        }
    }
}
