package com.prismml.bonsai.runtime

data class PerformanceMetrics(
    val promptTokens: Int,
    val promptMilliseconds: Double,
    val generatedTokens: Int,
    val generationMilliseconds: Double,
) {
    val promptTokensPerSecond: Double
        get() = if (promptMilliseconds > 0.0) promptTokens * 1000.0 / promptMilliseconds else 0.0

    val generationTokensPerSecond: Double
        get() = if (generationMilliseconds > 0.0) generatedTokens * 1000.0 / generationMilliseconds else 0.0
}
