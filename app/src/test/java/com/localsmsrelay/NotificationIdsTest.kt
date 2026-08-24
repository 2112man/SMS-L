package com.localsmsrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationIdsTest {
    @Test
    fun foregroundServiceUsesFixedReservedId() {
        assertEquals(1001, NotificationIds.SERVICE)
    }

    @Test
    fun consecutiveRoomRowsProduceIndependentSmsNotificationIds() {
        val first = NotificationIds.incomingSms(1)
        val second = NotificationIds.incomingSms(2)
        val third = NotificationIds.incomingSms(3)

        assertEquals(listOf(2000, 2001, 2002), listOf(first, second, third))
        assertNotEquals(first, second)
        assertNotEquals(second, third)
        assertNotEquals(NotificationIds.SERVICE, first)
    }

    @Test
    fun pendingIntentRequestCodesAreSeparate() {
        val notificationId = NotificationIds.incomingSms(583921)
        assertEquals(notificationId, NotificationIds.contentRequestCode(notificationId))
        assertNotEquals(
            NotificationIds.contentRequestCode(notificationId),
            NotificationIds.copyRequestCode(notificationId)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun databaseIdMustBePositive() {
        NotificationIds.incomingSms(0)
    }
}
