package com.example.open.reviewer.commitchecklist.template

object TemplateDiffBuilder {
    fun buildUnifiedDiff(
        path: String,
        oldText: String,
        newText: String,
    ): String {
        val oldLines = oldText.replace("\r\n", "\n").split("\n")
        val newLines = newText.replace("\r\n", "\n").split("\n")

        val max = maxOf(oldLines.size, newLines.size)
        val body =
            buildString {
                for (i in 0 until max) {
                    val oldLine = oldLines.getOrNull(i)
                    val newLine = newLines.getOrNull(i)
                    when {
                        oldLine == newLine && oldLine != null -> append(" ").append(oldLine).append('\n')
                        oldLine != null && newLine != null -> {
                            append("-").append(oldLine).append('\n')
                            append("+").append(newLine).append('\n')
                        }
                        oldLine != null -> append("-").append(oldLine).append('\n')
                        newLine != null -> append("+").append(newLine).append('\n')
                    }
                }
            }

        return buildString {
            append("--- ").append(path).append('\n')
            append("+++ ").append(path).append('\n')
            append("@@ -1,").append(oldLines.size).append(" +1,").append(newLines.size).append(" @@\n")
            append(body)
        }
    }
}

