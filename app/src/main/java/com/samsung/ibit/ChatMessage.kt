package com.samsung.ibit

data class ChatMessage(
    val id: Long,
    val role: String,
    val text: String,
    val meta: String? = null,
    val reasoning: String? = null,
    val isStreaming: Boolean = false,
)
