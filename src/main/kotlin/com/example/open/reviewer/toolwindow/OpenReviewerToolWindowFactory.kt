package com.example.open.reviewer.toolwindow

import com.example.open.reviewer.architecture.ui.ArchitectureFastScanPanel
import com.example.open.reviewer.commitchecklist.ui.RepoSetupStatusPanel
import com.example.open.reviewer.tour.ui.OpenReviewrToursPanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JTabbedPane

class OpenReviewerToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val tabs = JTabbedPane()
        val architecturePanel = ArchitectureFastScanPanel(project)
        tabs.addTab("Startup Risk", OpenReviewerToolWindowContent(project))
        tabs.addTab("Architecture", architecturePanel)
        tabs.addTab("Tours", OpenReviewrToursPanel(project))
        tabs.addTab("Repo Setup", RepoSetupStatusPanel(project))
        tabs.addChangeListener {
            if (tabs.selectedComponent === architecturePanel) {
                architecturePanel.onTabOpened()
            }
        }

        val content =
            ContentFactory.getInstance()
                .createContent(tabs, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
