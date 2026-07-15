package com.prismml.bonsai

/** Strict validation for an HTTP response to `Range: bytes=<offset>-`. */
internal object ModelDownloadProtocol {
    private val contentRangePattern = Regex(
        pattern = """bytes\s+(\d+)-(\d+)/(\d+)""",
        option = RegexOption.IGNORE_CASE,
    )

    fun isExpectedContentRange(header: String?, expectedStart: Long, expectedTotal: Long): Boolean {
        if (header == null || expectedStart !in 0L until expectedTotal) return false
        val match = contentRangePattern.matchEntire(header.trim()) ?: return false
        val start = match.groupValues[1].toLongOrNull() ?: return false
        val end = match.groupValues[2].toLongOrNull() ?: return false
        val total = match.groupValues[3].toLongOrNull() ?: return false
        return start == expectedStart && end == expectedTotal - 1L && total == expectedTotal
    }
}
