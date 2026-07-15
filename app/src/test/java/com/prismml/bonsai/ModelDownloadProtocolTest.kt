package com.prismml.bonsai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadProtocolTest {
    @Test
    fun acceptsExpectedResumeRange() {
        assertTrue(
            ModelDownloadProtocol.isExpectedContentRange(
                "bytes 4194304-1158654495/1158654496",
                expectedStart = 4_194_304L,
                expectedTotal = 1_158_654_496L,
            ),
        )
    }

    @Test
    fun rejectsRangeStartingAtWrongOffset() {
        assertFalse(
            ModelDownloadProtocol.isExpectedContentRange(
                "bytes 0-1158654495/1158654496",
                expectedStart = 4_194_304L,
                expectedTotal = 1_158_654_496L,
            ),
        )
    }

    @Test
    fun rejectsWrongTotalOrIncompleteEnd() {
        assertFalse(
            ModelDownloadProtocol.isExpectedContentRange(
                "bytes 4194304-999999999/1158654496",
                expectedStart = 4_194_304L,
                expectedTotal = 1_158_654_496L,
            ),
        )
        assertFalse(
            ModelDownloadProtocol.isExpectedContentRange(
                "bytes 4194304-1158654495/1158654497",
                expectedStart = 4_194_304L,
                expectedTotal = 1_158_654_496L,
            ),
        )
    }

    @Test
    fun rejectsMalformedOrUnsatisfiedRanges() {
        assertFalse(ModelDownloadProtocol.isExpectedContentRange(null, 1L, 10L))
        assertFalse(ModelDownloadProtocol.isExpectedContentRange("bytes */10", 1L, 10L))
        assertFalse(ModelDownloadProtocol.isExpectedContentRange("not-a-range", 1L, 10L))
    }
}
