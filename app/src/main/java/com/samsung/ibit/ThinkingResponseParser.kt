package com.samsung.ibit

internal data class ParsedModelResponse(
    val reasoning: String,
    val answer: String,
    val reasoningComplete: Boolean,
)

/** Parses the Qwen-style reasoning protocol from the aggregate streamed response. */
internal object ThinkingResponseParser {
    private const val OPEN_TAG = "<think>"
    private const val CLOSE_TAG = "</think>"

    fun parse(rawText: String, thinkingEnabled: Boolean): ParsedModelResponse {
        val text = rawText.trimStart()
        if (text.isEmpty()) {
            return ParsedModelResponse("", "", reasoningComplete = !thinkingEnabled)
        }

        val emittedOpenTag = text.startsWith(OPEN_TAG, ignoreCase = true)
        val contentStart = if (emittedOpenTag) OPEN_TAG.length else 0
        val closeIndex = text.indexOf(CLOSE_TAG, startIndex = contentStart, ignoreCase = true)

        if (thinkingEnabled) {
            if (closeIndex >= 0) {
                return ParsedModelResponse(
                    reasoning = text.substring(contentStart, closeIndex).trim(),
                    answer = text.substring(closeIndex + CLOSE_TAG.length).trim(),
                    reasoningComplete = true,
                )
            }
            return ParsedModelResponse(
                reasoning = removePartialClosingTag(text.substring(contentStart)).trim(),
                answer = "",
                reasoningComplete = false,
            )
        }

        // Direct mode normally emits no tags because the template prefills an
        // empty thinking block. Tolerate a model that emits one anyway, but do
        // not delete literal tags appearing later in an otherwise normal answer.
        if (emittedOpenTag) {
            if (closeIndex >= 0) {
                return ParsedModelResponse(
                    reasoning = text.substring(contentStart, closeIndex).trim(),
                    answer = text.substring(closeIndex + CLOSE_TAG.length).trim(),
                    reasoningComplete = true,
                )
            }
            return ParsedModelResponse(
                reasoning = removePartialClosingTag(text.substring(contentStart)).trim(),
                answer = "",
                reasoningComplete = false,
            )
        }

        return ParsedModelResponse("", text.trim(), reasoningComplete = true)
    }

    private fun removePartialClosingTag(text: String): String {
        for (prefixLength in CLOSE_TAG.length - 1 downTo 1) {
            if (text.endsWith(CLOSE_TAG.take(prefixLength), ignoreCase = true)) {
                return text.dropLast(prefixLength)
            }
        }
        return text
    }
}
