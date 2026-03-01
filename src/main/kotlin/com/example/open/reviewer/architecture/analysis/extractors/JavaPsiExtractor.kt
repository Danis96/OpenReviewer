package com.example.open.reviewer.architecture.analysis.extractors

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.util.Locale

data class JavaTypeDeclaration(
    val kind: KotlinDeclarationKind,
    val name: String,
    val supertypes: List<String>,
    val annotations: List<String>,
)

data class JavaPsiFileFacts(
    val path: String,
    val imports: List<String>,
    val declarations: List<JavaTypeDeclaration>,
    val entrypoints: List<String>,
    val annotations: List<String>,
    val signals: Set<AndroidPsiSignal>,
)

data class JavaPsiExtractionResult(
    val files: List<JavaPsiFileFacts>,
    val scannedFiles: Int,
    val skippedLargeFiles: Int,
    val unresolvedFiles: Int,
) {
    fun allSignals(): Set<AndroidPsiSignal> = files.flatMap { it.signals }.toSet()
}

class JavaPsiExtractor(
    private val maxFileSizeBytes: Long = 1_200_000L,
) {
    fun extract(
        project: Project,
        javaPaths: List<String>,
        indicator: ProgressIndicator? = null,
    ): JavaPsiExtractionResult {
        val psiManager = PsiManager.getInstance(project)
        var scanned = 0
        var skippedLarge = 0
        var unresolved = 0
        val output = mutableListOf<JavaPsiFileFacts>()
        val total = javaPaths.size.coerceAtLeast(1)

        javaPaths.sorted().forEachIndexed { index, path ->
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
                ReadAction.compute<JavaPsiFileFacts?, Throwable> {
                    val psiFile = psiManager.findFile(vFile) ?: return@compute null
                    extractFromPsi(psiFile, path)
                }
            if (facts == null) {
                unresolved += 1
            } else {
                output += facts
            }

            indicator?.text2 = "Java PSI extraction ${index + 1}/$total • extracted=${output.size}"
        }

        return JavaPsiExtractionResult(
            files = output.sortedBy { it.path },
            scannedFiles = scanned,
            skippedLargeFiles = skippedLarge,
            unresolvedFiles = unresolved,
        )
    }

    private fun extractFromPsi(
        psiFile: PsiFile,
        path: String,
    ): JavaPsiFileFacts? {
        if (!path.lowercase(Locale.ROOT).endsWith(".java")) return null

        val imports = extractImportsReflective(psiFile)
        val declarations = extractTypeDeclarationsReflective(psiFile)
        val entrypoints = extractEntrypoints(psiFile.text, declarations)
        val annotations = declarations.flatMap { it.annotations }.distinct().sorted()
        val signals = detectSignals(imports, declarations, annotations, psiFile.text)
        return JavaPsiFileFacts(
            path = path,
            imports = imports,
            declarations = declarations,
            entrypoints = entrypoints,
            annotations = annotations,
            signals = signals,
        )
    }

    private fun extractImportsReflective(psiFile: PsiFile): List<String> {
        val javaFileClass = classForNameOrNull("com.intellij.psi.PsiJavaFile") ?: return extractImportsFallback(psiFile.text)
        if (!javaFileClass.isInstance(psiFile)) return extractImportsFallback(psiFile.text)
        val importList = psiFile.callNoArg("getImportList") ?: return extractImportsFallback(psiFile.text)
        val statements = importList.callNoArg("getImportStatements") as? Array<*> ?: return extractImportsFallback(psiFile.text)
        return statements
            .mapNotNull { stmt -> stmt?.callNoArg("getQualifiedName")?.toString() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    private fun extractTypeDeclarationsReflective(psiFile: PsiFile): List<JavaTypeDeclaration> {
        val javaFileClass = classForNameOrNull("com.intellij.psi.PsiJavaFile") ?: return emptyList()
        if (!javaFileClass.isInstance(psiFile)) return emptyList()
        val classes = psiFile.callNoArg("getClasses") as? Array<*> ?: return emptyList()

        return classes.mapNotNull { decl ->
            if (decl == null) return@mapNotNull null
            val name = decl?.callNoArg("getName")?.toString().orEmpty()
            if (name.isBlank()) return@mapNotNull null

            val isInterface = (decl.callNoArg("isInterface") as? Boolean) == true
            val kind = if (isInterface) KotlinDeclarationKind.INTERFACE else KotlinDeclarationKind.CLASS

            val extendsList = decl.callNoArg("getExtendsListTypes") as? Array<*> ?: emptyArray<Any>()
            val implementsList = decl.callNoArg("getImplementsListTypes") as? Array<*> ?: emptyArray<Any>()
            val supertypes =
                (extendsList.asList() + implementsList.asList())
                    .mapNotNull { it?.callNoArg("getCanonicalText")?.toString() ?: it?.toString() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()

            val modifierList = decl.callNoArg("getModifierList")
            val annotations =
                (modifierList?.callNoArg("getAnnotations") as? Array<*>)
                    ?.mapNotNull { it?.callNoArg("getQualifiedName")?.toString() ?: it?.callNoArg("getText")?.toString() }
                    ?.map { it.substringAfterLast('.').removePrefix("@").trim() }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    ?.sorted()
                    .orEmpty()

            JavaTypeDeclaration(
                kind = kind,
                name = name,
                supertypes = supertypes,
                annotations = annotations,
            )
        }
    }

    private fun extractEntrypoints(
        text: String,
        declarations: List<JavaTypeDeclaration>,
    ): List<String> {
        val points = mutableSetOf<String>()
        if (Regex("\\bpublic\\s+static\\s+void\\s+main\\s*\\(").containsMatchIn(text)) points += "main"
        val hasOnCreate = Regex("\\bvoid\\s+onCreate\\s*\\(").containsMatchIn(text)
        declarations.forEach { decl ->
            val superText = decl.supertypes.joinToString(" ").lowercase(Locale.ROOT)
            if (hasOnCreate && ("activity" in superText || "fragment" in superText || "application" in superText)) {
                points += "${decl.name}.onCreate"
            }
        }
        return points.toList().sorted()
    }

    private fun detectSignals(
        imports: List<String>,
        declarations: List<JavaTypeDeclaration>,
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
        if ("androidx.compose" in importText || "setcontent(" in lowerText || "@composable" in lowerText) signals += AndroidPsiSignal.ANDROID_COMPOSE
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
            .map { it.removePrefix("import ").removeSuffix(";").trim() }
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
