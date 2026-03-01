package com.example.open.reviewer.architecture.analysis.extractors

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

enum class DartExtractionMode {
    PSI,
    FALLBACK,
}

enum class DartSignal {
    FLUTTER_WIDGET,
    FLUTTER_PROVIDER,
    FLUTTER_BLOC,
    FLUTTER_RIVERPOD,
    FLUTTER_JSON_SERIALIZABLE,
    FLUTTER_FREEZED,
}

data class DartPsiFileFacts(
    val path: String,
    val imports: List<String>,
    val widgets: List<String>,
    val changeNotifiers: List<String>,
    val blocCubits: List<String>,
    val riverpodTypes: List<String>,
    val hasMain: Boolean,
    val signals: Set<DartSignal>,
    val extractionMode: DartExtractionMode,
    val confidence: Double,
)

data class DartPsiExtractionResult(
    val files: List<DartPsiFileFacts>,
    val scannedFiles: Int,
    val unresolvedFiles: Int,
    val psiFiles: Int,
    val fallbackFiles: Int,
) {
    fun allSignals(): Set<DartSignal> = files.flatMap { it.signals }.toSet()
}

class DartPsiExtractor(
    private val fallbackExtractor: DartFallbackTextExtractor = DartFallbackTextExtractor(),
    private val maxFileSizeBytes: Long = 1_200_000L,
) {
    fun extract(
        project: Project,
        dartPaths: List<String>,
        indicator: ProgressIndicator? = null,
    ): DartPsiExtractionResult {
        val psiManager = PsiManager.getInstance(project)
        var scanned = 0
        var unresolved = 0
        var psiFiles = 0
        var fallbackFiles = 0
        val out = mutableListOf<DartPsiFileFacts>()
        val total = dartPaths.size.coerceAtLeast(1)

        dartPaths.sorted().forEachIndexed { index, path ->
            indicator?.checkCanceled()
            val vFile = LocalFileSystem.getInstance().findFileByPath(path)
            if (vFile == null || vFile.isDirectory) {
                unresolved += 1
                return@forEachIndexed
            }
            if (vFile.length > maxFileSizeBytes) {
                val fallback = fallbackExtractor.extractFile(path)
                if (fallback != null) {
                    out += fallback
                    fallbackFiles += 1
                } else {
                    unresolved += 1
                }
                return@forEachIndexed
            }
            scanned += 1

            val psiFact =
                ReadAction.compute<DartPsiFileFacts?, Throwable> {
                    val psiFile = psiManager.findFile(vFile) ?: return@compute null
                    if (!isDartPsiFile(psiFile)) return@compute null
                    extractFromPsi(psiFile, path)
                }

            if (psiFact != null) {
                out += psiFact
                psiFiles += 1
            } else {
                val fallback = fallbackExtractor.extractFile(path)
                if (fallback != null) {
                    out += fallback
                    fallbackFiles += 1
                } else {
                    unresolved += 1
                }
            }

            indicator?.text2 = "Dart extraction ${index + 1}/$total • psi=$psiFiles fallback=$fallbackFiles"
        }

        return DartPsiExtractionResult(
            files = out.sortedBy { it.path },
            scannedFiles = scanned,
            unresolvedFiles = unresolved,
            psiFiles = psiFiles,
            fallbackFiles = fallbackFiles,
        )
    }

    private fun isDartPsiFile(psiFile: PsiFile): Boolean {
        val languageIds =
            buildSet {
                add(psiFile.language.id)
                add(psiFile.viewProvider.baseLanguage.id)
            }.map { it.lowercase(Locale.ROOT) }
        if (languageIds.any { it == "dart" }) return true

        // Defensive fallback: keeps compatibility across Dart plugin class/package changes.
        return psiFile.javaClass.name.lowercase(Locale.ROOT).contains("dart")
    }

    private fun extractFromPsi(
        psiFile: PsiFile,
        path: String,
    ): DartPsiFileFacts {
        val text = psiFile.text
        val imports = extractImports(text)
        val widgets = extractClassNamesByBase(text, "Widget")
        val notifiers = extractClassNamesByBase(text, "ChangeNotifier")
        val blocs = extractClassNamesByAnyBase(text, listOf("Bloc<", "Cubit<"))
        val riverpods = extractClassNamesByAnyBase(text, listOf("StateNotifier<", "Notifier<"))
        val hasMain = Regex("""\b(?:Future<\s*void\s*>\s+|void\s+)main\s*\(""").containsMatchIn(text)
        val signals = detectSignals(text, imports, widgets, notifiers, blocs, riverpods)

        return DartPsiFileFacts(
            path = normalizePath(path),
            imports = imports,
            widgets = widgets,
            changeNotifiers = notifiers,
            blocCubits = blocs,
            riverpodTypes = riverpods,
            hasMain = hasMain,
            signals = signals,
            extractionMode = DartExtractionMode.PSI,
            confidence = 0.8,
        )
    }

    private fun detectSignals(
        text: String,
        imports: List<String>,
        widgets: List<String>,
        notifiers: List<String>,
        blocs: List<String>,
        riverpods: List<String>,
    ): Set<DartSignal> {
        val lowerText = text.lowercase(Locale.ROOT)
        val lowerImports = imports.joinToString(" ").lowercase(Locale.ROOT)
        val signals = mutableSetOf<DartSignal>()
        if (widgets.isNotEmpty() || "extends statelesswidget" in lowerText || "extends statefulwidget" in lowerText) signals += DartSignal.FLUTTER_WIDGET
        if (notifiers.isNotEmpty() || "provider" in lowerImports || "changenotifierprovider" in lowerText) signals += DartSignal.FLUTTER_PROVIDER
        if (blocs.isNotEmpty() || "flutter_bloc" in lowerImports || "blocprovider" in lowerText) signals += DartSignal.FLUTTER_BLOC
        if (riverpods.isNotEmpty() || "riverpod" in lowerImports || "providerscope" in lowerText) signals += DartSignal.FLUTTER_RIVERPOD
        if ("@jsonserializable" in lowerText || "json_serializable" in lowerImports) signals += DartSignal.FLUTTER_JSON_SERIALIZABLE
        if ("@freezed" in lowerText || "freezed" in lowerImports) signals += DartSignal.FLUTTER_FREEZED
        return signals
    }

    private fun extractImports(text: String): List<String> {
        return Regex("""^\s*import\s+['"]([^'"]+)['"]\s*;""", RegexOption.MULTILINE)
            .findAll(text)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
    }

    private fun extractClassNamesByBase(
        text: String,
        base: String,
    ): List<String> {
        return Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)\s+extends\s+$base\b""")
            .findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
    }

    private fun extractClassNamesByAnyBase(
        text: String,
        baseTokens: List<String>,
    ): List<String> {
        return Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)\s+extends\s+([A-Za-z_][A-Za-z0-9_<> ,]*)""")
            .findAll(text)
            .mapNotNull { match ->
                val className = match.groupValues[1]
                val base = match.groupValues[2]
                if (baseTokens.any { it in base }) className else null
            }
            .distinct()
            .sorted()
            .toList()
    }

    private fun normalizePath(path: String): String = Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/')
}

class DartFallbackTextExtractor {
    fun extractFiles(paths: List<String>): DartPsiExtractionResult {
        val files = paths.sorted().mapNotNull { extractFile(it) }
        return DartPsiExtractionResult(
            files = files,
            scannedFiles = files.size,
            unresolvedFiles = paths.size - files.size,
            psiFiles = 0,
            fallbackFiles = files.size,
        )
    }

    fun extractFile(path: String): DartPsiFileFacts? {
        val p = Path.of(path)
        if (!Files.isRegularFile(p)) return null
        val text =
            runCatching {
                val bytes = Files.readAllBytes(p)
                String(bytes, StandardCharsets.UTF_8)
            }.getOrNull() ?: return null

        val imports = Regex("""^\s*import\s+['"]([^'"]+)['"]\s*;""", RegexOption.MULTILINE)
            .findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
        val widgets = Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)\s+extends\s+(?:StatelessWidget|StatefulWidget)\b""")
            .findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
        val notifiers = Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)\s+extends\s+ChangeNotifier\b""")
            .findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
        val blocs = Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)\s+extends\s+(?:Bloc<|Cubit<)[^}]*""")
            .findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
        val riverpods = Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)\s+extends\s+(?:StateNotifier<|Notifier<)[^}]*""")
            .findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
        val hasMain = Regex("""\b(?:Future<\s*void\s*>\s+|void\s+)main\s*\(""").containsMatchIn(text)
        val lower = text.lowercase(Locale.ROOT)
        val importJoined = imports.joinToString(" ").lowercase(Locale.ROOT)
        val signals = mutableSetOf<DartSignal>()
        if (widgets.isNotEmpty()) signals += DartSignal.FLUTTER_WIDGET
        if (notifiers.isNotEmpty() || "provider" in importJoined || "changenotifierprovider" in lower) signals += DartSignal.FLUTTER_PROVIDER
        if (blocs.isNotEmpty() || "flutter_bloc" in importJoined || "blocprovider" in lower) signals += DartSignal.FLUTTER_BLOC
        if (riverpods.isNotEmpty() || "riverpod" in importJoined || "providerscope" in lower) signals += DartSignal.FLUTTER_RIVERPOD
        if ("@jsonserializable" in lower || "json_serializable" in importJoined) signals += DartSignal.FLUTTER_JSON_SERIALIZABLE
        if ("@freezed" in lower || "freezed" in importJoined) signals += DartSignal.FLUTTER_FREEZED

        return DartPsiFileFacts(
            path = p.toAbsolutePath().normalize().toString().replace('\\', '/'),
            imports = imports,
            widgets = widgets,
            changeNotifiers = notifiers,
            blocCubits = blocs,
            riverpodTypes = riverpods,
            hasMain = hasMain,
            signals = signals,
            extractionMode = DartExtractionMode.FALLBACK,
            confidence = 0.5,
        )
    }
}
