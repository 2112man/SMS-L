package com.localsmsrelay

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationChannelsInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun serviceAndIncomingSmsUseIndependentChannelsAndIds() {
        NotificationHelper.updateIncomingSmsVibration(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val service = manager.getNotificationChannel(NotificationHelper.CHANNEL_SERVICE)
        val incoming = manager.getNotificationChannel(NotificationHelper.CHANNEL_INCOMING_SMS)

        assertEquals("relay_service_v3", service.id)
        assertEquals("后台监听", service.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, service.importance)
        assertFalse(service.shouldVibrate())
        assertFalse(service.canShowBadge())

        assertEquals("incoming_sms_v2", incoming.id)
        assertEquals("短信提醒", incoming.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, incoming.importance)
        assertTrue(incoming.canShowBadge())
        assertNotEquals(NotificationIds.SERVICE, NotificationIds.incomingSms(1))

        assertNull(manager.getNotificationChannel("relay_service"))
        assertNull(manager.getNotificationChannel("relay_sms_vibrate"))
        assertNull(manager.getNotificationChannel("relay_sms_silent"))
    }

    @Test
    fun incomingSmsCancellationDoesNotUseCancelAll() {
        if (!NotificationHelper.canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)

        NotificationHelper.showTestSms(context, "角标取消测试", null)
        assertTrue(waitForNotification(manager, NotificationIds.TEST_SMS, present = true))
        NotificationHelper.cancelIncomingSms(context, NotificationIds.TEST_SMS)
        assertTrue(waitForNotification(manager, NotificationIds.TEST_SMS, present = false))

        NotificationHelper.showTestSms(context, "批量取消测试", null)
        assertTrue(waitForNotification(manager, NotificationIds.TEST_SMS, present = true))
        NotificationHelper.cancelAllIncomingSms(context)
        assertTrue(waitForNotification(manager, NotificationIds.TEST_SMS, present = false))
    }

    @Test
    fun notificationPendingIntentCancelsOnlyTappedMessage() {
        if (!NotificationHelper.canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val firstDatabaseId = 7_000_001L
        val secondDatabaseId = 7_000_002L
        val firstNotificationId = NotificationIds.incomingSms(firstDatabaseId)
        val secondNotificationId = NotificationIds.incomingSms(secondDatabaseId)
        NotificationHelper.showSms(
            context, firstDatabaseId, firstNotificationId, 2, "95555", "第一条验证码123456", "123456"
        )
        NotificationHelper.showSms(
            context, secondDatabaseId, secondNotificationId, 2, "95555", "第二条验证码654321", "654321"
        )
        assertTrue(waitForNotification(manager, firstNotificationId, present = true))
        assertTrue(waitForNotification(manager, secondNotificationId, present = true))

        manager.activeNotifications
            .first { it.id == firstNotificationId }
            .notification
            .contentIntent
            .send()

        assertTrue(waitForNotification(manager, firstNotificationId, present = false))
        assertTrue(waitForNotification(manager, secondNotificationId, present = true))
        NotificationHelper.cancelIncomingSms(context, secondNotificationId)
    }

    private fun waitForNotification(
        manager: NotificationManager,
        notificationId: Int,
        present: Boolean
    ): Boolean {
        repeat(20) {
            val found = manager.activeNotifications.any { it.id == notificationId }
            if (found == present) return true
            SystemClock.sleep(50)
        }
        return false
    }
}
