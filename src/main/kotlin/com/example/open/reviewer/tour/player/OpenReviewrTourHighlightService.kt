package com.example.open.reviewer.tour.player

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

class OpenReviewrTourHighlightService {
    private var currentHighlighter: RangeHighlighter? = null

    fun highlightLine(
        editor: Editor,
        lineNumber: Int,
    ) {
        clear()
        val document = editor.document
        if (document.lineCount <= 0) return

        val lineIndex = (lineNumber - 1).coerceIn(0, document.lineCount - 1)
        val startOffset = document.getLineStartOffset(lineIndex)
        val endOffset = document.getLineEndOffset(lineIndex)
        val attributes =
            TextAttributes(
                null,
                JBColor(Color(0xE6F2FF), Color(0x223A52)),
                JBColor(Color(0x5AA9FF), Color(0x6DB8FF)),
                EffectType.ROUNDED_BOX,
                Font.PLAIN,
            )

        val highlighter =
            editor.markupModel.addRangeHighlighter(
                startOffset,
                endOffset,
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
        currentHighlighter = highlighter
    }

    fun clear() {
        val highlighter = currentHighlighter
        if (highlighter != null && highlighter.isValid) {
            highlighter.dispose()
        }
        currentHighlighter = null
    }
}
