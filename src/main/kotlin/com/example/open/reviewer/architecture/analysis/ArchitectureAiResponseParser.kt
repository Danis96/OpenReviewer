package com.example.open.reviewer.architecture.analysis

import java.nio.file.Path

data class ArchitectureDetectedPattern(
    val name: String,
    val confidence: Double,
    val evidencePaths: List<String>,
)

data class ArchitectureGraphHintEdge(
    val source: String,
    val target: String,
    val weight: Double,
    val reason: String,
)

data class ArchitectureGraphHints(
    val focusPaths: List<String> = emptyList(),
    val focusEdges: List<ArchitectureGraphHintEdge> = emptyList(),
)

data class ArchitectureAiParsedResponse(
    val schemaVersion: Int,
    val architectureGuess: String,
    val confidence: Double,
    val patterns: List<ArchitectureDetectedPattern>,
    val graphHints: ArchitectureGraphHints = ArchitectureGraphHints(),
)

data class ArchitectureAiParseResult(
    val parsed: Boolean,
    val response: ArchitectureAiParsedResponse?,
    val errors: List<String>,
)

class ArchitectureAiResponseParser {
    fun parse(raw: String): ArchitectureAiParseResult {
        return runCatching {
            parseStrict(sanitizeRaw(raw))
        }.getOrElse { error ->
            ArchitectureAiParseResult(parsed = false, response = null, errors = listOf(error.message ?: "Invalid JSON"))
        }
    }

    private fun sanitizeRaw(raw: String): String {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (!trimmed.startsWith("```")) return trimmed

        val lines = trimmed.lines()
        if (lines.isEmpty()) return trimmed
        val first = lines.first().trim()
        if (!first.startsWith("```")) return trimmed

        val endIndex = lines.indexOfLast { it.trim() == "```" }
        if (endIndex <= 0) return trimmed
        return lines.subList(1, endIndex).joinToString("\n").trim()
    }

    private fun parseStrict(raw: String): ArchitectureAiParseResult {
        val root = JsonParser(raw).parseObject()
        val errors = mutableListOf<String>()

        val schemaVersion = (root["schemaVersion"] as? Number)?.toInt()
        if (schemaVersion == null) errors += "schemaVersion is required."
        if (schemaVersion != null && schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            errors += "Unsupported schemaVersion=$schemaVersion."
        }

        val architectureGuess = (root["architectureGuess"] as? String)?.trim().orEmpty()
        if (architectureGuess.isBlank()) errors += "architectureGuess is required."

        val confidence = (root["confidence"] as? Number)?.toDouble()
        if (confidence == null) {
            errors += "confidence is required."
        } else if (confidence !in 0.0..1.0) {
            errors += "confidence must be in [0,1]."
        }

        val patterns =
            (root["patterns"] as? List<*>)
                ?.mapIndexedNotNull { index, item ->
                    val obj = item as? Map<*, *> ?: run {
                        errors += "patterns[$index] must be object."
                        return@mapIndexedNotNull null
                    }
                    val name = (obj["name"] as? String)?.trim().orEmpty()
                    val patternConfidence = (obj["confidence"] as? Number)?.toDouble()
                    val evidence =
                        (obj["evidencePaths"] as? List<*>)
                            ?.mapNotNull { it as? String }
                            ?.map { it.trim() }
                            ?.filter { it.isNotBlank() }
                            .orEmpty()

                    if (name.isBlank()) errors += "patterns[$index].name is required."
                    if (patternConfidence == null) errors += "patterns[$index].confidence is required."
                    if (patternConfidence != null && patternConfidence !in 0.0..1.0) {
                        errors += "patterns[$index].confidence must be in [0,1]."
                    }
                    if (evidence.isEmpty()) errors += "patterns[$index].evidencePaths is required."
                    evidence.forEach { path ->
                        if (!isValidEvidencePath(path)) {
                            errors += "patterns[$index].evidencePaths contains invalid path: $path"
                        }
                    }

                    if (name.isBlank() || patternConfidence == null || evidence.isEmpty()) {
                        null
                    } else {
                        ArchitectureDetectedPattern(
                            name = name,
                            confidence = patternConfidence,
                            evidencePaths = evidence,
                        )
                    }
                }
                .orEmpty()

        if (patterns.isEmpty()) errors += "patterns must contain at least one valid item."
        val graphHints = parseGraphHints(root["graphHints"])
        val response =
            if (errors.isEmpty() && schemaVersion != null && confidence != null) {
                ArchitectureAiParsedResponse(
                    schemaVersion = schemaVersion,
                    architectureGuess = architectureGuess,
                    confidence = confidence,
                    patterns = patterns,
                    graphHints = graphHints,
                )
            } else {
                null
            }
        return ArchitectureAiParseResult(
            parsed = response != null,
            response = response,
            errors = errors,
        )
    }

    private fun isValidEvidencePath(path: String): Boolean {
        if (path.startsWith("/")) return false
        if (path.contains('\\')) return false
        if ("://" in path) return false
        return runCatching {
            val normalized = Path.of(path).normalize().toString().replace('\\', '/')
            normalized.isNotBlank() && !normalized.startsWith("..") && "/../" !in normalized
        }.getOrElse { false }
    }

    private fun parseGraphHints(raw: Any?): ArchitectureGraphHints {
        val obj = raw as? Map<*, *> ?: return ArchitectureGraphHints()
        val focusPaths =
            (obj["focusPaths"] as? List<*>)
                .orEmpty()
                .mapNotNull { it as? String }
                .map { it.trim() }
                .filter { it.isNotBlank() && isValidEvidencePath(it) }
                .distinct()
                .take(MAX_HINT_PATHS)
        val focusEdges =
            (obj["focusEdges"] as? List<*>)
                .orEmpty()
                .mapNotNull { edge ->
                    val edgeObj = edge as? Map<*, *> ?: return@mapNotNull null
                    val source = (edgeObj["source"] as? String)?.trim().orEmpty()
                    val target = (edgeObj["target"] as? String)?.trim().orEmpty()
                    if (source.isBlank() || target.isBlank()) return@mapNotNull null
                    if (!isValidEvidencePath(source) || !isValidEvidencePath(target)) return@mapNotNull null
                    val weight = ((edgeObj["weight"] as? Number)?.toDouble() ?: 0.65).coerceIn(0.0, 1.0)
                    val reason = (edgeObj["reason"] as? String)?.trim().orEmpty().take(120)
                    ArchitectureGraphHintEdge(
                        source = source,
                        target = target,
                        weight = weight,
                        reason = reason,
                    )
                }.distinctBy { "${it.source}->${it.target}" }
                .take(MAX_HINT_EDGES)
        return ArchitectureGraphHints(
            focusPaths = focusPaths,
            focusEdges = focusEdges,
        )
    }

    companion object {
        private const val SUPPORTED_SCHEMA_VERSION = 1
        private const val MAX_HINT_PATHS = 80
        private const val MAX_HINT_EDGES = 120
    }
}

private class JsonParser(
    private val input: String,
) {
    private var i = 0

    fun parseObject(): Map<String, Any?> {
        skipWs()
        val value = parseValue()
        skipWs()
        if (i != input.length) error("Trailing characters after JSON root.")
        return value as? Map<String, Any?> ?: error("JSON root must be an object.")
    }

    private fun parseValue(): Any? {
        skipWs()
        if (i >= input.length) error("Unexpected end of input.")
        return when (input[i]) {
            '{' -> parseObjectInternal()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> error("Unexpected token '${input[i]}'.")
        }
    }

    private fun parseObjectInternal(): Map<String, Any?> {
        expect('{')
        skipWs()
        val out = linkedMapOf<String, Any?>()
        if (peek('}')) {
            expect('}')
            return out
        }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            expect(':')
            val value = parseValue()
            out[key] = value
            skipWs()
            if (peek('}')) {
                expect('}')
                return out
            }
            expect(',')
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        skipWs()
        val out = mutableListOf<Any?>()
        if (peek(']')) {
            expect(']')
            return out
        }
        while (true) {
            out += parseValue()
            skipWs()
            if (peek(']')) {
                expect(']')
                return out
            }
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (i < input.length) {
            val c = input[i++]
            if (c == '"') return out.toString()
            if (c == '\\') {
                if (i >= input.length) error("Invalid escape.")
                val esc = input[i++]
                when (esc) {
                    '"', '\\', '/' -> out.append(esc)
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (i + 4 > input.length) error("Invalid unicode escape.")
                        val hex = input.substring(i, i + 4)
                        out.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> error("Unsupported escape '\\$esc'.")
                }
            } else {
                out.append(c)
            }
        }
        error("Unterminated string.")
    }

    private fun parseNumber(): Number {
        val start = i
        if (input[i] == '-') i++
        while (i < input.length && input[i].isDigit()) i++
        if (i < input.length && input[i] == '.') {
            i++
            while (i < input.length && input[i].isDigit()) i++
        }
        if (i < input.length && (input[i] == 'e' || input[i] == 'E')) {
            i++
            if (i < input.length && (input[i] == '+' || input[i] == '-')) i++
            while (i < input.length && input[i].isDigit()) i++
        }
        val token = input.substring(start, i)
        return token.toDoubleOrNull() ?: error("Invalid number: $token")
    }

    private fun parseLiteral(
        token: String,
        value: Any?,
    ): Any? {
        if (!input.startsWith(token, i)) error("Expected $token.")
        i += token.length
        return value
    }

    private fun skipWs() {
        while (i < input.length && input[i].isWhitespace()) i++
    }

    private fun expect(c: Char) {
        skipWs()
        if (i >= input.length || input[i] != c) error("Expected '$c'.")
        i++
    }

    private fun peek(c: Char): Boolean {
        skipWs()
        return i < input.length && input[i] == c
    }

    private fun error(message: String): Nothing {
        throw IllegalArgumentException("JSON parse error at offset $i: $message")
    }
}
