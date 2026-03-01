package com.example.open.reviewer.architecture.analysis

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

class ImportDependencyGraphBuilder {
    fun build(
        root: Path,
        allFiles: List<String>,
        manifestEntrypoints: List<String>,
    ): ArchitectureGraphSnapshot {
        val rootNormalized = normalizePath(root)
        val sourceFiles =
            allFiles
                .asSequence()
                .map { Path.of(it) }
                .filter { Files.isRegularFile(it) }
                .filter { it.extension.lowercase() in SUPPORTED_EXTENSIONS }
                .mapNotNull { parseFile(it, rootNormalized) }
                .toList()

        val fqNameToPath = linkedMapOf<String, String>()
        val simpleNameToPaths = linkedMapOf<String, MutableSet<String>>()
        val byPath = sourceFiles.associateBy { it.path }
        val typeToFiles = linkedMapOf<String, MutableSet<SourceFileFacts>>()
        sourceFiles.forEach { file ->
            file.declaredFqNames.forEach { fq ->
                fqNameToPath.putIfAbsent(fq, file.path)
                val simple = fq.substringAfterLast('.')
                simpleNameToPaths.getOrPut(simple) { linkedSetOf() } += file.path
            }
            file.declaredSimpleNames.forEach { type ->
                typeToFiles.getOrPut(type) { linkedSetOf() } += file
            }
        }

        val edges = linkedSetOf<Pair<String, String>>()
        sourceFiles.forEach { file ->
            when (file.language) {
                SourceLanguage.KOTLIN,
                SourceLanguage.JAVA,
                -> resolveJvmEdges(file, fqNameToPath, simpleNameToPaths).forEach { target ->
                    if (target != file.path) edges += file.path to target
                }
                SourceLanguage.DART -> resolveDartEdges(file, byPath.keys, root).forEach { target ->
                    if (target != file.path) edges += file.path to target
                }
            }
        }
        buildTypeUsageEdges(sourceFiles, typeToFiles).forEach { edge -> edges += edge }

        val entrypointNodes = linkedSetOf<String>()
        manifestEntrypoints.forEach { entrypoint ->
            val normalized = entrypoint.trim()
            if (normalized.isBlank()) return@forEach
            val node = "entrypoint:$normalized"
            entrypointNodes += node
            val targets = resolveEntrypointTarget(normalized, fqNameToPath, simpleNameToPaths)
            if (targets.isEmpty()) return@forEach
            targets.forEach { target -> edges += node to target }
        }

        val nodeWeights = linkedMapOf<String, Int>()
        edges.forEach { (from, to) ->
            nodeWeights[from] = (nodeWeights[from] ?: 0) + 1
            nodeWeights[to] = (nodeWeights[to] ?: 0) + 1
        }
        entrypointNodes.forEach { node -> nodeWeights.putIfAbsent(node, 0) }

        val topNodes =
            nodeWeights.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { entry -> "${displayNode(entry.key, rootNormalized)}(${entry.value})" }
        val trimmedEdges = trimEdges(edges, rootNormalized)

        return ArchitectureGraphSnapshot(
            nodeCount = nodeWeights.size,
            edgeCount = edges.size,
            topNodes = topNodes,
            entrypoints = manifestEntrypoints,
            trimmedEdges = trimmedEdges,
        )
    }

    private fun trimEdges(
        edges: Set<Pair<String, String>>,
        root: String,
    ): List<String> {
        return edges
            .sortedByDescending { edgePriority(it.first, it.second) }
            .take(MAX_TRIMMED_EDGES)
            .map { (from, to) -> "${displayNode(from, root)} -> ${displayNode(to, root)}" }
    }

    private fun edgePriority(
        from: String,
        to: String,
    ): Int {
        var score = 0
        if (from.startsWith("entrypoint:")) score += 50
        if (isUiLike(from) && isVmLike(to)) score += 40
        if (isVmLike(from) && isRepoLike(to)) score += 40
        if (isRepoLike(to)) score += 8
        if (isTestLike(from) || isTestLike(to)) score -= 26
        if (isConfigLike(from) && isConfigLike(to)) score -= 12
        return score
    }

    private fun isUiLike(path: String): Boolean {
        val lower = path.lowercase()
        return listOf("activity", "fragment", "screen", "widget", "page", "view").any { it in lower }
    }

    private fun isVmLike(path: String): Boolean = "viewmodel" in path.lowercase()

    private fun isRepoLike(path: String): Boolean {
        val lower = path.lowercase()
        return "repository" in lower || "/repo" in lower || "repo." in lower
    }

    private fun isTestLike(path: String): Boolean {
        val lower = path.lowercase()
        return "/test/" in lower || "/tests/" in lower || "/androidtest/" in lower || lower.endsWith("_test.dart") || lower.endsWith("test.kt")
    }

    private fun isConfigLike(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".gradle") || lower.endsWith(".gradle.kts") || "manifest" in lower || "/config/" in lower
    }

    private fun resolveJvmEdges(
        file: SourceFileFacts,
        fqNameToPath: Map<String, String>,
        simpleNameToPaths: Map<String, Set<String>>,
    ): Set<String> {
        val targets = linkedSetOf<String>()
        file.imports.forEach { raw ->
            val clean = cleanJvmImport(raw)
            if (clean.isBlank()) return@forEach
            if (clean.endsWith(".*")) {
                val prefix = clean.removeSuffix(".*")
                fqNameToPath.entries
                    .asSequence()
                    .filter { (fqName, _) -> fqName.startsWith("$prefix.") }
                    .map { (_, path) -> path }
                    .forEach { targets += it }
                return@forEach
            }

            val exact = fqNameToPath[clean]
            if (exact != null) {
                targets += exact
                return@forEach
            }

            val simple = clean.substringAfterLast('.')
            val candidates = simpleNameToPaths[simple].orEmpty()
            if (candidates.size == 1) {
                targets += candidates.first()
            }
        }
        return targets
    }

    private fun resolveDartEdges(
        file: SourceFileFacts,
        knownPaths: Set<String>,
        root: Path,
    ): Set<String> {
        val targets = linkedSetOf<String>()
        val sourcePath = Path.of(file.path)
        file.imports.forEach { uri ->
            val normalized = uri.trim()
            if (normalized.isBlank()) return@forEach
            val target =
                when {
                    normalized.startsWith("dart:") -> null
                    normalized.startsWith("package:flutter") -> null
                    normalized.startsWith("package:") -> resolvePackageDartImport(normalized, root)
                    else -> normalizePath(sourcePath.parent.resolve(normalized).normalize())
                }
            if (target != null && target in knownPaths) {
                targets += target
            }
        }
        return targets
    }

    private fun resolvePackageDartImport(
        importUri: String,
        root: Path,
    ): String? {
        val trimmed = importUri.removePrefix("package:")
        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash >= trimmed.length - 1) return null
        val packageRelative = trimmed.substring(slash + 1)
        val candidate = root.resolve("lib").resolve(packageRelative).normalize()
        return normalizePath(candidate)
    }

    private fun resolveEntrypointTarget(
        entrypoint: String,
        fqNameToPath: Map<String, String>,
        simpleNameToPaths: Map<String, Set<String>>,
    ): Set<String> {
        val exact = fqNameToPath[entrypoint]
        if (exact != null) return setOf(exact)
        val simple = entrypoint.substringAfterLast('.')
        val candidates = simpleNameToPaths[simple].orEmpty()
        return if (candidates.size == 1) setOf(candidates.first()) else emptySet()
    }

    private fun parseFile(
        path: Path,
        root: String,
    ): SourceFileFacts? {
        val content =
            runCatching {
                String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            }.getOrNull() ?: return null
        val normalized = normalizePath(path)
        val extension = path.extension.lowercase()
        return when (extension) {
            "kt" -> {
                val jvmDecl = parseJvmDeclarations(content)
                SourceFileFacts(
                    path = normalized,
                    language = SourceLanguage.KOTLIN,
                    imports = parseKotlinImports(content),
                    declaredFqNames = jvmDecl,
                    declaredSimpleNames = jvmDecl.map { it.substringAfterLast('.') }.toSet(),
                    usedTypeTokens = parseUsedTypeTokens(content),
                    role = inferRole(path = path, declaredTypeNames = jvmDecl.map { it.substringAfterLast('.') }),
                )
            }
            "java" -> {
                val jvmDecl = parseJvmDeclarations(content)
                SourceFileFacts(
                    path = normalized,
                    language = SourceLanguage.JAVA,
                    imports = parseJavaImports(content),
                    declaredFqNames = jvmDecl,
                    declaredSimpleNames = jvmDecl.map { it.substringAfterLast('.') }.toSet(),
                    usedTypeTokens = parseUsedTypeTokens(content),
                    role = inferRole(path = path, declaredTypeNames = jvmDecl.map { it.substringAfterLast('.') }),
                )
            }
            "dart" -> {
                val declarationName = dartFqName(path, root)
                val declaredSimple = declarationName.substringAfterLast('/').substringBeforeLast('.')
                SourceFileFacts(
                    path = normalized,
                    language = SourceLanguage.DART,
                    imports = parseDartImports(content),
                    declaredFqNames = listOf(declarationName),
                    declaredSimpleNames = setOf(declaredSimple),
                    usedTypeTokens = parseUsedTypeTokens(content),
                    role = inferRole(path = path, declaredTypeNames = listOf(declaredSimple)),
                )
            }
            else -> null
        }
    }

    private fun parseJvmDeclarations(content: String): List<String> {
        val packageName =
            Regex("""^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""", RegexOption.MULTILINE)
                .find(content)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                .orEmpty()
        val names =
            Regex("""\b(?:class|interface|object|enum\s+class|record)\s+([A-Za-z_][A-Za-z0-9_]*)""")
                .findAll(content)
                .map { it.groupValues[1] }
                .toSet()
                .sorted()
        if (packageName.isBlank()) return names
        return names.map { "$packageName.$it" }
    }

    private fun parseUsedTypeTokens(content: String): Set<String> {
        return Regex("""\b[A-Z][A-Za-z0-9_]{2,}\b""")
            .findAll(content)
            .map { it.value }
            .filterNot { it in TYPE_TOKEN_STOPWORDS }
            .toSet()
    }

    private fun buildTypeUsageEdges(
        sourceFiles: List<SourceFileFacts>,
        typeToFiles: Map<String, Set<SourceFileFacts>>,
    ): Set<Pair<String, String>> {
        val edges = linkedSetOf<Pair<String, String>>()
        sourceFiles.forEach { file ->
            when (file.role) {
                FileRole.UI -> {
                    val candidates = findTargets(file, typeToFiles, targetRole = FileRole.VM)
                    candidates.forEach { target -> edges += file.path to target.path }
                }
                FileRole.VM -> {
                    val candidates = findTargets(file, typeToFiles, targetRole = FileRole.REPO)
                    candidates.forEach { target -> edges += file.path to target.path }
                }
                else -> Unit
            }
        }
        return edges
    }

    private fun findTargets(
        file: SourceFileFacts,
        typeToFiles: Map<String, Set<SourceFileFacts>>,
        targetRole: FileRole,
    ): Set<SourceFileFacts> {
        val importTypeNames = file.imports.mapNotNull { simpleTypeFromImport(it) }.toSet()
        val referencedTypes = file.usedTypeTokens + importTypeNames
        val targets = linkedSetOf<SourceFileFacts>()
        referencedTypes.forEach { type ->
            typeToFiles[type]
                .orEmpty()
                .asSequence()
                .filter { it.path != file.path }
                .filter { it.role == targetRole }
                .forEach { targets += it }
        }
        return targets
    }

    private fun simpleTypeFromImport(importValue: String): String? {
        val trimmed = importValue.trim()
        if (trimmed.isBlank()) return null
        val slashName = trimmed.substringAfterLast('/').substringBefore('?').substringBefore('#')
        if (slashName.contains('.')) {
            val base = slashName.substringBeforeLast('.')
            if (base.isNotBlank()) return base
        }
        val dotName = trimmed.substringAfterLast('.')
        return dotName.ifBlank { null }
    }

    private fun inferRole(
        path: Path,
        declaredTypeNames: List<String>,
    ): FileRole {
        val filename = path.name.lowercase()
        val names = declaredTypeNames.map { it.lowercase() }
        if (names.any { it.endsWith("viewmodel") } || filename.contains("viewmodel")) return FileRole.VM
        if (
            names.any { it.endsWith("repository") || it.endsWith("repo") } ||
            filename.contains("repository") ||
            filename.contains("repo")
        ) {
            return FileRole.REPO
        }
        if (
            names.any { name ->
                name.endsWith("activity") ||
                    name.endsWith("fragment") ||
                    name.endsWith("screen") ||
                    name.endsWith("widget") ||
                    name.endsWith("page") ||
                    name.endsWith("view")
            } ||
            filename.contains("activity") ||
            filename.contains("fragment") ||
            filename.contains("screen") ||
            filename.contains("widget") ||
            filename.contains("page") ||
            filename.contains("view")
        ) {
            return FileRole.UI
        }
        return FileRole.OTHER
    }

    private fun parseKotlinImports(content: String): List<String> {
        return Regex("""^\s*import\s+([^\n]+)$""", RegexOption.MULTILINE)
            .findAll(content)
            .map { it.groupValues[1].trim() }
            .map { it.substringBefore(" as ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
    }

    private fun parseJavaImports(content: String): List<String> {
        return Regex("""^\s*import\s+([^\n;]+);""", RegexOption.MULTILINE)
            .findAll(content)
            .map { it.groupValues[1].trim() }
            .map { it.removePrefix("static ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
    }

    private fun parseDartImports(content: String): List<String> {
        return Regex("""^\s*import\s+['"]([^'"]+)['"]\s*;""", RegexOption.MULTILINE)
            .findAll(content)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
    }

    private fun dartFqName(
        path: Path,
        root: String,
    ): String {
        val normalized = normalizePath(path)
        val rootPrefix = if (root.endsWith("/")) root else "$root/"
        return if (normalized.startsWith(rootPrefix)) {
            normalized.removePrefix(rootPrefix)
        } else {
            path.name
        }
    }

    private fun displayNode(
        node: String,
        root: String,
    ): String {
        if (node.startsWith("entrypoint:")) return node
        val rootPrefix = if (root.endsWith("/")) root else "$root/"
        return node.removePrefix(rootPrefix)
    }

    private fun cleanJvmImport(raw: String): String {
        return raw
            .trim()
            .removePrefix("static ")
            .substringBefore(" as ")
            .removeSuffix(";")
            .trim()
    }

    private fun normalizePath(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/')

    private enum class SourceLanguage {
        KOTLIN,
        JAVA,
        DART,
    }

    private data class SourceFileFacts(
        val path: String,
        val language: SourceLanguage,
        val imports: List<String>,
        val declaredFqNames: List<String>,
        val declaredSimpleNames: Set<String>,
        val usedTypeTokens: Set<String>,
        val role: FileRole,
    )

    private enum class FileRole {
        UI,
        VM,
        REPO,
        OTHER,
    }

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("kt", "java", "dart")
        private const val MAX_TRIMMED_EDGES = 40
        private val TYPE_TOKEN_STOPWORDS =
            setOf(
                "String",
                "Int",
                "Long",
                "Double",
                "Float",
                "Boolean",
                "Unit",
                "List",
                "Set",
                "Map",
                "MutableList",
                "MutableSet",
                "MutableMap",
                "Any",
            )
    }
}
