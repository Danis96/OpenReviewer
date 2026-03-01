package com.example.open.reviewer.architecture.model

data class FileFacts(
    var lineCount: Int = 0,
    var classCount: Int = 0,
    var functionCount: Int = 0,
    var importCount: Int = 0,
    var signalTags: MutableList<String> = mutableListOf(),
)

data class FileSummary(
    var headline: String = "",
    var keyPoints: MutableList<String> = mutableListOf(),
)
