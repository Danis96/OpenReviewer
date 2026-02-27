package com.example.open.reviewer.editor

import com.example.open.reviewer.analysis.Suggestion
import com.example.open.reviewer.analysis.SuggestionImpact
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import javax.swing.Icon

@Service(Service.Level.PROJECT)
class EditorSuggestionDecorationService(
    private val project: Project,
) {
    private val highlightersByEditor = linkedMapOf<Editor, MutableList<RangeHighlighter>>()

    fun showSuggestions(suggestions: List<Suggestion>) {
        clearAll()
        val anchored = suggestions.filter { !it.filePath.isNullOrBlank() && (it.line ?: 0) > 0 }
        if (anchored.isEmpty()) return

        val byFile = anchored.groupBy { it.filePath.orEmpty() }
        val fileEditorManager = FileEditorManager.getInstance(project)
        byFile.forEach { (path, fileSuggestions) ->
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(path) ?: return@forEach
            val textEditors = fileEditorManager.getEditors(virtualFile).filterIsInstance<TextEditor>()
            textEditors.forEach { textEditor ->
                fileSuggestions.forEach { suggestion ->
                    addHighlighter(textEditor.editor, suggestion)
                }
            }
        }
    }

    fun clearAll() {
        highlightersByEditor.values.flatten().forEach { highlighter ->
            if (highlighter.isValid) {
                highlighter.dispose()
            }
        }
        highlightersByEditor.clear()
    }

    private fun addHighlighter(
        editor: Editor,
        suggestion: Suggestion,
    ) {
        val lineNumber = suggestion.line ?: return
        val document = editor.document
        if (document.lineCount <= 0) return
        val lineIndex = (lineNumber - 1).coerceIn(0, document.lineCount - 1)
        val startOffset = document.getLineStartOffset(lineIndex)
        val endOffset = document.getLineEndOffset(lineIndex)
        val highlighter =
            editor.markupModel.addRangeHighlighter(
                startOffset,
                endOffset,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                null,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
        highlighter.gutterIconRenderer = SuggestionGutterIconRenderer(suggestion)
        highlighter.errorStripeTooltip = suggestionTooltip(suggestion)
        highlightersByEditor.getOrPut(editor) { mutableListOf() }.add(highlighter)
    }

    private fun suggestionTooltip(suggestion: Suggestion): String {
        return formatTooltipHtml(suggestion)
    }

    companion object {
        fun getInstance(project: Project): EditorSuggestionDecorationService =
            project.getService(EditorSuggestionDecorationService::class.java)
    }
}

private class SuggestionGutterIconRenderer(
    private val suggestion: Suggestion,
) : GutterIconRenderer() {
    override fun getIcon(): Icon {
        return when (suggestion.impact) {
            SuggestionImpact.LOW -> AllIcons.General.Information
            SuggestionImpact.MEDIUM -> AllIcons.General.Warning
            SuggestionImpact.HIGH -> AllIcons.General.Error
        }
    }

    override fun getTooltipText(): String {
        return formatTooltipHtml(suggestion)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SuggestionGutterIconRenderer) return false
        return suggestion == other.suggestion
    }

    override fun hashCode(): Int = suggestion.hashCode()
}

private fun formatTooltipHtml(suggestion: Suggestion): String {
    val title = StringUtil.escapeXmlEntities(suggestion.title.trim())
    val details = StringUtil.escapeXmlEntities(suggestion.details.trim())
    return if (details.isBlank()) {
        "<html><b>$title</b></html>"
    } else {
        "<html><b>$title</b><br/>$details</html>"
    }
}
