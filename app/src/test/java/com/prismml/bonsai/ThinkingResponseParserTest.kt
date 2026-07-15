package com.prismml.bonsai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingResponseParserTest {
    @Test
    fun parsesPrefilledOpeningTagStream() {
        val parsed = ThinkingResponseParser.parse(
            "Add 17 and 25 to get 42.\n</think>\n\nThe answer is 42.",
            thinkingEnabled = true,
        )

        assertEquals("Add 17 and 25 to get 42.", parsed.reasoning)
        assertEquals("The answer is 42.", parsed.answer)
        assertTrue(parsed.reasoningComplete)
    }

    @Test
    fun toleratesAnEmittedOpeningTag() {
        val parsed = ThinkingResponseParser.parse(
            "<think>brief reasoning</think>final",
            thinkingEnabled = true,
        )

        assertEquals("brief reasoning", parsed.reasoning)
        assertEquals("final", parsed.answer)
    }

    @Test
    fun hidesPartialClosingTagDuringStreaming() {
        val parsed = ThinkingResponseParser.parse("brief reasoning</thi", thinkingEnabled = true)

        assertEquals("brief reasoning", parsed.reasoning)
        assertEquals("", parsed.answer)
        assertFalse(parsed.reasoningComplete)
    }

    @Test
    fun leavesDirectAnswerUnchanged() {
        val parsed = ThinkingResponseParser.parse(
            "Use the literal <think> tag in your markup.",
            thinkingEnabled = false,
        )

        assertEquals("", parsed.reasoning)
        assertEquals("Use the literal <think> tag in your markup.", parsed.answer)
        assertTrue(parsed.reasoningComplete)
    }

    @Test
    fun toleratesUnexpectedLeadingThinkingBlockInDirectMode() {
        val parsed = ThinkingResponseParser.parse(
            "<think>hidden</think>visible",
            thinkingEnabled = false,
        )

        assertEquals("hidden", parsed.reasoning)
        assertEquals("visible", parsed.answer)
    }
}
