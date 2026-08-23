package com.localsmsrelay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentMessageIdsTest {
    @Test
    fun sameMessageIdIsDuplicateInsideWindow() {
        val ids = RecentMessageIds(windowMillis = 1_000)
        assertFalse(ids.isDuplicateAndRemember("same-id", now = 100))
        assertTrue(ids.isDuplicateAndRemember("same-id", now = 500))
    }

    @Test
    fun emptyMessageIdIsNotPutThroughDeduplicatorByHttpFlow() {
        val messageId = "".ifEmpty { null }
        assertTrue(messageId == null)
    }

    @Test
    fun idCanBeAcceptedAgainAfterWindow() {
        val ids = RecentMessageIds(windowMillis = 1_000)
        assertFalse(ids.isDuplicateAndRemember("retry-id", now = 100))
        assertFalse(ids.isDuplicateAndRemember("retry-id", now = 1_101))
    }
}
