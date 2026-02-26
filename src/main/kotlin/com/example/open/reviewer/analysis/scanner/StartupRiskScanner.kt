package com.example.open.reviewer.analysis.scanner

import com.example.open.reviewer.analysis.Finding
import com.example.open.reviewer.analysis.FindingSeverity
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.util.ArrayDeque

data class ScannerNode(
    val functionName: String,
    val functionBody: String,
    val file: VirtualFile,
    val functionLine: Int,
    val depth: Int,
)

class StartupRiskScanner(
    private val maxDepth: Int = 2,
) {
    private val kotlinFunRegex = Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
    private val javaFunRegex =
        Regex(
            "\\b(?:public|private|protected|static|final|synchronized|native|abstract|\\s)+" +
                "[A-Za-z_][A-Za-z0-9_<>,.?\\[\\]]*\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(",
        )
    private val swiftFunRegex = Regex("\\bfunc\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
    private val dartFunRegex = Regex("\\b(?:Future<[^>]+>\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")

    private val callRegex = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")

    private val syncIoPatterns =
        listOf(
            PatternRule(
                "Synchronous file I/O in startup path",
                "File I/O near startup can delay first frame; defer or make async.",
                FindingSeverity.WARN,
                Regex("File(InputStream|OutputStream|Reader|Writer)?\\s*\\("),
            ),
            PatternRule(
                "SharedPreferences synchronous commit",
                "Synchronous SharedPreferences.commit() blocks startup; prefer apply().",
                FindingSeverity.WARN,
                Regex("SharedPreferences[^\\n]{0,120}commit\\s*\\("),
            ),
            PatternRule(
                "Synchronous database open on startup",
                "Opening databases on startup can be expensive; initialize lazily.",
                FindingSeverity.WARN,
                Regex("(SQLiteDatabase\\.open|Room\\.databaseBuilder|openDatabase\\s*\\()"),
            ),
        )

    private val networkPatterns =
        listOf(
            PatternRule(
                "Network call in startup path",
                "Startup code appears to trigger network setup/calls. Move non-critical calls off critical path.",
                FindingSeverity.WARN,
                Regex("(HttpClient|Retrofit|OkHttpClient|URLSession|HttpURLConnection|URL\\s*\\()"),
            ),
        )

    private val blockingPatterns =
        listOf(
            PatternRule(
                "Blocking call in startup path",
                "Blocking calls during startup hurt responsiveness. Use asynchronous alternatives.",
                FindingSeverity.CRITICAL,
                Regex("(Thread\\.sleep\\s*\\(|sleep\\s*\\(|runBlocking\\s*\\()"),
            ),
        )

    fun scan(
        entryPoints: List<DiscoveredEntryPoint>,
        indicator: ProgressIndicator,
    ): List<Finding> {
        if (entryPoints.isEmpty()) return emptyList()

        val indices = buildFunctionIndices(entryPoints, indicator)
        if (indices.byPathAndName.isEmpty()) return emptyList()

        val queue = ArrayDeque<ScannerNode>()
        val visited = mutableSetOf<String>()
        val findings = mutableListOf<Finding>()

        entryPoints.forEach { entry ->
            val key = key(entry.file.path, entry.name)
            val candidates = indices.byPathAndName[key].orEmpty()
            candidates.forEach { node ->
                queue.add(node.copy(depth = 0))
            }
        }

        while (queue.isNotEmpty()) {
            indicator.checkCanceled()
            val node = queue.removeFirst()
            val nodeVisitKey = "${node.file.path}:${node.functionName}:${node.functionLine}:${node.depth}"
            if (!visited.add(nodeVisitKey)) continue

            findings += scanNodeForRisks(node)

            if (node.depth >= maxDepth) continue

            extractCalls(node.functionBody)
                .flatMap { callName ->
                    val sameFile = indices.byPathAndName[key(node.file.path, callName)].orEmpty()
                    sameFile.ifEmpty { indices.byName[callName].orEmpty() }
                }
                .forEach { calledNode ->
                    queue.add(calledNode.copy(depth = node.depth + 1))
                }
        }

        return findings.distinctBy { listOf(it.title, it.filePath, it.line, it.codeSnippet).joinToString("|") }
    }

    internal fun scanNodesForTest(nodes: List<ScannerNode>): List<Finding> {
        return nodes
            .flatMap { scanNodeForRisks(it) }
            .distinctBy { listOf(it.title, it.filePath, it.line, it.codeSnippet).joinToString("|") }
    }

    private fun buildFunctionIndices(
        entryPoints: List<DiscoveredEntryPoint>,
        indicator: ProgressIndicator,
    ): FunctionIndices {
        val roots =
            entryPoints.map { it.file.path }
                .mapNotNull { path ->
                    val base = path.substringBeforeLast('/', "")
                    if (base.isBlank()) null else LocalFileSystem.getInstance().findFileByPath(base)
                }
                .distinctBy { it.path }

        val files = mutableListOf<VirtualFile>()
        roots.forEach { root ->
            VfsUtilCore.iterateChildrenRecursively(
                root,
                { !it.isDirectory || !it.name.startsWith(".") },
                {
                    if (!it.isDirectory && isCodeFile(it)) files.add(it)
                    true
                },
            )
        }

        val byPathAndName = mutableMapOf<String, MutableList<ScannerNode>>()
        val byName = mutableMapOf<String, MutableList<ScannerNode>>()
        files.forEachIndexed { indexPos, file ->
            indicator.checkCanceled()
            indicator.text2 = "Building call index (${indexPos + 1}/${files.size})"
            val content = readText(file) ?: return@forEachIndexed
            extractFunctions(content, file).forEach { node ->
                byPathAndName.getOrPut(key(file.path, node.functionName)) { mutableListOf() }.add(node)
                byName.getOrPut(node.functionName) { mutableListOf() }.add(node)
            }
        }

        return FunctionIndices(byPathAndName = byPathAndName, byName = byName)
    }

    private fun scanNodeForRisks(node: ScannerNode): List<Finding> {
        val findings = mutableListOf<Finding>()

        syncIoPatterns.forEach { rule ->
            rule.find(node)?.let { findings.add(it) }
        }

        networkPatterns.forEach { rule ->
            rule.find(node)?.let { findings.add(it) }
        }

        blockingPatterns.forEach { rule ->
            rule.find(node)?.let { findings.add(it) }
        }

        if (node.depth == 0) {
            detectHeavyInitialization(node)?.let { findings.add(it) }
        }

        return findings
    }

    private fun detectHeavyInitialization(node: ScannerNode): Finding? {
        val loopCount = Regex("\\b(for|while)\\s*\\(").findAll(node.functionBody).count()
        val instantiations =
            Regex("\\bnew\\s+[A-Z][A-Za-z0-9_]*").findAll(node.functionBody).count() +
                Regex("=\\s*[A-Z][A-Za-z0-9_]*\\s*\\(").findAll(node.functionBody).count()

        val isHeavy = loopCount >= 3 || instantiations >= 8
        if (!isHeavy) return null

        val snippet =
            node.functionBody.lineSequence().firstOrNull {
                it.contains("for(") || it.contains("for (") || it.contains("while(") || it.contains("while (") || it.contains("new ")
            }
                ?.trim()
                ?.take(120)

        return Finding(
            title = "Heavy initialization in startup entry",
            description = "Entry point performs many loops/instantiations; move non-critical setup off startup path.",
            severity = FindingSeverity.WARN,
            filePath = node.file.path,
            line = node.functionLine,
            codeSnippet = snippet,
        )
    }

    private fun extractFunctions(
        content: String,
        file: VirtualFile,
    ): List<ScannerNode> {
        val matches = mutableListOf<Pair<String, IntRange>>()

        listOf(kotlinFunRegex, javaFunRegex, swiftFunRegex, dartFunRegex).forEach { regex ->
            regex.findAll(content).forEach { match ->
                val name = match.groupValues.getOrNull(1).orEmpty()
                if (name.isNotBlank()) {
                    matches.add(name to match.range)
                }
            }
        }

        if (matches.isEmpty()) return emptyList()

        val sorted = matches.sortedBy { it.second.first }
        return sorted.mapIndexed { idx, (name, range) ->
            val start = range.first
            val nextStart = sorted.getOrNull(idx + 1)?.second?.first ?: content.length
            val (bodyStart, bodyEnd) = findBodyRange(content, start, nextStart)
            val functionBody = content.substring(bodyStart, bodyEnd)
            val line = content.take(start).count { it == '\n' } + 1
            ScannerNode(
                functionName = name,
                functionBody = functionBody,
                file = file,
                functionLine = line,
                depth = 0,
            )
        }
    }

    private fun findBodyRange(
        content: String,
        signatureStart: Int,
        hardLimit: Int,
    ): Pair<Int, Int> {
        val openBrace = content.indexOf('{', signatureStart)
        if (openBrace < 0 || openBrace >= hardLimit) {
            return signatureStart to hardLimit
        }

        var depth = 0
        var end = openBrace
        while (end < content.length && end < hardLimit) {
            when (content[end]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        end += 1
                        break
                    }
                }
            }
            end += 1
        }

        return signatureStart to end.coerceAtMost(content.length)
    }

    private fun extractCalls(functionBody: String): List<String> {
        val excluded = setOf("if", "for", "while", "when", "switch", "catch", "return", "fun", "void")
        return callRegex.findAll(functionBody)
            .map { it.groupValues[1] }
            .filter { it !in excluded }
            .distinct()
            .toList()
    }

    private fun key(
        path: String,
        functionName: String,
    ): String = "$path#$functionName"

    private fun isCodeFile(file: VirtualFile): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".kt") || name.endsWith(".java") || name.endsWith(".swift") || name.endsWith(".dart")
    }

    private fun readText(file: VirtualFile): String? {
        if (file.length > 600_000) return null
        return try {
            VfsUtilCore.loadText(file)
        } catch (_: IOException) {
            null
        }
    }

    private data class PatternRule(
        val title: String,
        val description: String,
        val severity: FindingSeverity,
        val regex: Regex,
    ) {
        fun find(node: ScannerNode): Finding? {
            val match = regex.find(node.functionBody) ?: return null
            val offset = node.functionBody.take(match.range.first).count { it == '\n' }
            val snippet =
                node.functionBody.lineSequence()
                    .firstOrNull { it.contains(match.value) }
                    ?.trim()
                    ?.take(120)

            return Finding(
                title = title,
                description = description,
                severity = severity,
                filePath = node.file.path,
                line = node.functionLine + offset,
                codeSnippet = snippet,
            )
        }
    }

    private data class FunctionIndices(
        val byPathAndName: Map<String, List<ScannerNode>>,
        val byName: Map<String, List<ScannerNode>>,
    )
}
