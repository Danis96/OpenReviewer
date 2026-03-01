package com.example.open.reviewer.architecture.analysis.extractors

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.util.Locale

enum class AndroidPsiSignal {
    ANDROID_VIEWMODEL,
    ANDROID_ACTIVITY,
    ANDROID_FRAGMENT,
    ANDROID_COMPOSE,
    ANDROID_HILT,
    ANDROID_ROOM,
    ANDROID_RETROFIT,
}

enum class KotlinDeclarationKind {
    CLASS,
    INTERFACE,
    OBJECT,
}

data class KotlinTypeDeclaration(
    val kind: KotlinDeclarationKind,
    val name: String,
    val supertypes: List<String>,
    val annotations: List<String>,
)

data class KotlinPsiFileFacts(
    val path: String,
    val imports: List<String>,
    val declarations: List<KotlinTypeDeclaration>,
    val entrypoints: List<String>,
    val annotations: List<String>,
    val signals: Set<AndroidPsiSignal>,
)

data class KotlinPsiExtractionResult(
    val files: List<KotlinPsiFileFacts>,
    val scannedFiles: Int,
    val skippedLargeFiles: Int,
    val unresolvedFiles: Int,
) {
    fun allSignals(): Set<AndroidPsiSignal> = files.flatMap { it.signals }.toSet()
}

class KotlinPsiExtractor(
    private val maxFileSizeBytes: Long = 1_200_000L,
) {
    fun extract(
        project: Project,
        kotlinPaths: List<String>,
        indicator: ProgressIndicator? = null,
    ): KotlinPsiExtractionResult {
        val psiManager = PsiManager.getInstance(project)
        var scanned = 0
        var skippedLarge = 0
        var unresolved = 0
        val output = mutableListOf<KotlinPsiFileFacts>()
        val total = kotlinPaths.size.coerceAtLeast(1)

        kotlinPaths.sorted().forEachIndexed { index, path ->
            indicator?.checkCanceled()
            val vFile = LocalFileSystem.getInstance().findFileByPath(path)
            if (vFile == null || vFile.isDirectory) {
                unresolved += 1
                return@forEachIndexed
            }
            if (vFile.length > maxFileSizeBytes) {
                skippedLarge += 1
                return@forEachIndexed
            }
            scanned += 1

            val facts =
                ReadAction.compute<KotlinPsiFileFacts?, Throwable> {
                    val psiFile = psiManager.findFile(vFile) ?: return@compute null
                    extractFromPsi(psiFile, path)
                }

            if (facts == null) {
                unresolved += 1
            } else {
                output += facts
            }

            indicator?.text2 = "Kotlin PSI extraction ${index + 1}/$total • extracted=${output.size}"
            indicator?.fraction = ((index + 1) / total.toDouble()).coerceIn(0.0, 1.0)
        }

        return KotlinPsiExtractionResult(
            files = output.sortedBy { it.path },
            scannedFiles = scanned,
            skippedLargeFiles = skippedLarge,
            unresolvedFiles = unresolved,
        )
    }

    private fun extractFromPsi(
        psiFile: PsiFile,
        path: String,
    ): KotlinPsiFileFacts? {
        if (!path.lowercase(Locale.ROOT).endsWith(".kt")) return null

        val imports = extractImportsReflective(psiFile)
        val declarations = extractTypeDeclarationsReflective(psiFile)
        val entrypoints = extractEntrypoints(psiFile.text, declarations)
        val annotations = declarations.flatMap { it.annotations }.distinct().sorted()
        val signals = detectSignals(imports, declarations, annotations, psiFile.text)

        return KotlinPsiFileFacts(
            path = path,
            imports = imports,
            declarations = declarations,
            entrypoints = entrypoints,
            annotations = annotations,
            signals = signals,
        )
    }

    private fun extractImportsReflective(psiFile: PsiFile): List<String> {
        val ktFileClass = classForNameOrNull("org.jetbrains.kotlin.psi.KtFile") ?: return extractImportsFallback(psiFile.text)
        if (!ktFileClass.isInstance(psiFile)) return extractImportsFallback(psiFile.text)
        val directives = psiFile.callNoArg("getImportDirectives") as? List<*> ?: return extractImportsFallback(psiFile.text)
        val imports =
            directives.mapNotNull { directive ->
                val fqName = directive?.callNoArg("getImportedFqName")
                val asString = fqName?.callNoArg("asString") as? String
                asString ?: directive?.callNoArg("getText")?.toString()?.removePrefix("import ")?.trim()
            }
        return imports.distinct().sorted()
    }

    private fun extractTypeDeclarationsReflective(psiFile: PsiFile): List<KotlinTypeDeclaration> {
        val ktFileClass = classForNameOrNull("org.jetbrains.kotlin.psi.KtFile") ?: return emptyList()
        if (!ktFileClass.isInstance(psiFile)) return emptyList()
        val declarations = psiFile.callNoArg("getDeclarations") as? List<*> ?: return emptyList()

        return declarations.mapNotNull { decl ->
            val simpleName = decl?.javaClass?.simpleName ?: return@mapNotNull null
            if (simpleName != "KtClass" && simpleName != "KtObjectDeclaration") return@mapNotNull null
            val name = decl.callNoArg("getName")?.toString().orEmpty()
            if (name.isBlank()) return@mapNotNull null

            val kind =
                when {
                    simpleName == "KtObjectDeclaration" -> KotlinDeclarationKind.OBJECT
                    (decl.callNoArg("isInterface") as? Boolean) == true -> KotlinDeclarationKind.INTERFACE
                    else -> KotlinDeclarationKind.CLASS
                }
            val supertypes =
                (decl.callNoArg("getSuperTypeListEntries") as? List<*>)
                    ?.mapNotNull { it?.callNoArg("getText")?.toString()?.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    ?.sorted()
                    .orEmpty()
            val annotations =
                (decl.callNoArg("getAnnotationEntries") as? List<*>)
                    ?.mapNotNull { anno ->
                        val shortName = anno?.callNoArg("getShortName")?.callNoArg("asString")?.toString()
                        shortName ?: anno?.callNoArg("getText")?.toString()?.trim()?.removePrefix("@")
                    }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    ?.sorted()
                    .orEmpty()

            KotlinTypeDeclaration(
                kind = kind,
                name = name,
                supertypes = supertypes,
                annotations = annotations,
            )
        }
    }

    private fun extractEntrypoints(
        text: String,
        declarations: List<KotlinTypeDeclaration>,
    ): List<String> {
        val points = mutableSetOf<String>()
        if (Regex("\\bfun\\s+main\\s*\\(").containsMatchIn(text)) {
            points += "main"
        }
        val hasOnCreate = Regex("\\boverride\\s+fun\\s+onCreate\\s*\\(").containsMatchIn(text)
        declarations.forEach { decl ->
            val superText = decl.supertypes.joinToString(" ").lowercase(Locale.ROOT)
            if ("activity" in superText && hasOnCreate) points += "${decl.name}.onCreate"
            if ("fragment" in superText && hasOnCreate) points += "${decl.name}.onCreate"
            if ("application" in superText && hasOnCreate) points += "${decl.name}.onCreate"
        }
        return points.toList().sorted()
    }

    private fun detectSignals(
        imports: List<String>,
        declarations: List<KotlinTypeDeclaration>,
        annotations: List<String>,
        text: String,
    ): Set<AndroidPsiSignal> {
        val signals = mutableSetOf<AndroidPsiSignal>()
        val superText = declarations.flatMap { it.supertypes }.joinToString(" ").lowercase(Locale.ROOT)
        val names = declarations.map { it.name.lowercase(Locale.ROOT) }
        val importText = imports.joinToString(" ").lowercase(Locale.ROOT)
        val annotationText = annotations.joinToString(" ").lowercase(Locale.ROOT)
        val lowerText = text.lowercase(Locale.ROOT)

        if ("viewmodel" in superText || names.any { it.endsWith("viewmodel") }) signals += AndroidPsiSignal.ANDROID_VIEWMODEL
        if ("activity" in superText || names.any { it.endsWith("activity") }) signals += AndroidPsiSignal.ANDROID_ACTIVITY
        if ("fragment" in superText || names.any { it.endsWith("fragment") }) signals += AndroidPsiSignal.ANDROID_FRAGMENT
        if ("androidx.compose" in importText || "@composable" in lowerText || "setcontent(" in lowerText) signals += AndroidPsiSignal.ANDROID_COMPOSE
        if (
            "hiltandroidapp" in annotationText ||
            "androidentrypoint" in annotationText ||
            "installin" in annotationText ||
            "inject" in annotationText ||
            "dagger.hilt" in importText
        ) {
            signals += AndroidPsiSignal.ANDROID_HILT
        }
        if ("entity" in annotationText || "dao" in annotationText || "database" in annotationText || "androidx.room" in importText) {
            signals += AndroidPsiSignal.ANDROID_ROOM
        }
        if (
            "get" in annotationText ||
            "post" in annotationText ||
            "put" in annotationText ||
            "delete" in annotationText ||
            "retrofit2" in importText
        ) {
            signals += AndroidPsiSignal.ANDROID_RETROFIT
        }

        return signals
    }

    private fun extractImportsFallback(text: String): List<String> {
        return text
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
    }

    private fun Any.callNoArg(methodName: String): Any? {
        return runCatching {
            javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }?.invoke(this)
        }.getOrNull()
    }

    private fun classForNameOrNull(name: String): Class<*>? {
        return runCatching { Class.forName(name) }.getOrNull()
    }
}
