package com.example.open.reviewer.commitchecklist.gate

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

enum class CommitChecklistEnforcementMode {
    BLOCK,
    WARN,
}

data class CommitChecklistGateState(
    var enforcementMode: String = CommitChecklistEnforcementMode.BLOCK.name,
    var requireDescription: Boolean = false,
)

@Service(Service.Level.PROJECT)
@State(name = "OpenReviewerCommitChecklistGate", storages = [Storage("open-reviewer-commit-checklist-gate.xml")])
class CommitChecklistGateSettingsService : PersistentStateComponent<CommitChecklistGateState> {
    private var state = CommitChecklistGateState()

    override fun getState(): CommitChecklistGateState = state

    override fun loadState(state: CommitChecklistGateState) {
        this.state = state
    }

    fun enforcementMode(): CommitChecklistEnforcementMode {
        return runCatching { CommitChecklistEnforcementMode.valueOf(state.enforcementMode) }
            .getOrDefault(CommitChecklistEnforcementMode.BLOCK)
    }

    fun requireDescription(): Boolean = state.requireDescription

    companion object {
        fun getInstance(project: Project): CommitChecklistGateSettingsService =
            project.getService(CommitChecklistGateSettingsService::class.java)
    }
}

