package com.localsmsrelay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings

object NotificationHelper {
    const val CHANNEL_SERVICE = "relay_service_v3"
    const val CHANNEL_INCOMING_SMS = "incoming_sms_v2"
    const val SERVICE_NOTIFICATION_ID = NotificationIds.SERVICE
    const val RESTORE_NOTIFICATION_ID = NotificationIds.RESTORE_SERVICE

    private val obsoleteChannelIds = arrayOf(
        "relay_service",
        "relay_sms_vibrate",
        "relay_sms_silent",
        "relay_restore"
    )

    @Volatile
    private var channelsReady = false

    fun createChannels(context: Context) {
        if (channelsReady) return
        synchronized(this) {
            if (channelsReady) return
            val manager = context.getSystemService(NotificationManager::class.java)
            obsoleteChannelIds.forEach(manager::deleteNotificationChannel)

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "后台监听",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SMS-L 中继服务的低打扰常驻通知"
                setSound(null, null)
                enableVibration(false)
                vibrationPattern = null
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }

            val audio = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            val incomingSmsChannel = NotificationChannel(
                CHANNEL_INCOMING_SMS,
                "短信提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "收到 iPhone 转发短信时显示可见提醒"
                val vibrationEnabled = AppPrefs.vibrate(context)
                enableVibration(vibrationEnabled)
                vibrationPattern = if (vibrationEnabled) longArrayOf(0, 250, 120, 250) else null
                setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audio)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }

            manager.createNotificationChannels(listOf(serviceChannel, incomingSmsChannel))
            channelsReady = true
        }
    }

    fun updateIncomingSmsVibration(context: Context) {
        synchronized(this) {
            context.getSystemService(NotificationManager::class.java)
                .deleteNotificationChannel(CHANNEL_INCOMING_SMS)
            channelsReady = false
        }
        createChannels(context)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Built once for startForeground(); status broadcasts never rebuild or re-notify it. */
    fun serviceNotification(context: Context): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            SERVICE_OPEN_REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_MESSAGES, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            context,
            SERVICE_STOP_REQUEST_CODE,
            Intent(context, RelayService::class.java).setAction(RelayService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Local SMS Relay 正在运行")
            .setContentText("短信接收服务已启动")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, "停止服务", stop).build())
            .build()
    }

    fun showSms(
        context: Context,
        messageDatabaseId: Long,
        notificationId: Int,
        unreadCount: Int,
        sender: String?,
        text: String,
        otp: String?
    ) {
        require(notificationId == NotificationIds.incomingSms(messageDatabaseId)) {
            "notificationId must be derived from the Room message id"
        }
        showSmsWithId(
            context = context,
            notificationId = notificationId,
            messageDatabaseId = messageDatabaseId,
            unreadCount = unreadCount,
            sender = sender,
            text = text,
            otp = otp
        )
    }

    fun showTestSms(context: Context, text: String, otp: String?) {
        showSmsWithId(
            context = context,
            notificationId = NotificationIds.TEST_SMS,
            messageDatabaseId = null,
            unreadCount = 0,
            sender = null,
            text = text,
            otp = otp
        )
    }

    private fun showSmsWithId(
        context: Context,
        notificationId: Int,
        messageDatabaseId: Long?,
        unreadCount: Int,
        sender: String?,
        text: String,
        otp: String?
    ) {
        if (!canNotify(context)) return
        createChannels(context)
        check(notificationId != SERVICE_NOTIFICATION_ID) {
            "Incoming SMS notification must not reuse the foreground service id"
        }

        val content = NotificationContentFormatter.format(sender, text, otp)
        val openIntent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_MESSAGES, true)
            .putExtra(MainActivity.EXTRA_NOTIFICATION_ID, notificationId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (messageDatabaseId != null) {
            openIntent.putExtra(MainActivity.EXTRA_MESSAGE_ID, messageDatabaseId)
        }
        val openApp = PendingIntent.getActivity(
            context,
            NotificationIds.contentRequestCode(notificationId),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(context, CHANNEL_INCOMING_SMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(Notification.BigTextStyle().bigText(content.text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)

        if (unreadCount > 0) builder.setNumber(unreadCount)

        if (!otp.isNullOrBlank()) {
            val copyIntent = Intent(context, CopyOtpReceiver::class.java)
                .putExtra(CopyOtpReceiver.EXTRA_OTP, otp)
            val copy = PendingIntent.getBroadcast(
                context,
                NotificationIds.copyRequestCode(notificationId),
                copyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(Notification.Action.Builder(null, "复制验证码", copy).build())
        }

        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, builder.build())
    }

    /** Explicitly cancel one incoming-SMS notification; the service id is never accepted. */
    fun cancelIncomingSms(context: Context, notificationId: Int) {
        if (notificationId <= 0 || notificationId == SERVICE_NOTIFICATION_ID) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val activeSms = try {
            manager.activeNotifications.firstOrNull {
                it.id == notificationId && it.notification.channelId == CHANNEL_INCOMING_SMS
            }
        } catch (_: RuntimeException) {
            null
        }
        if (activeSms != null) {
            manager.cancel(activeSms.tag, activeSms.id)
        } else {
            // Still invoke cancel explicitly for the standard auto-cancel race after a tap.
            manager.cancel(notificationId)
        }
    }

    /** Cancel only notifications posted on incoming_sms_v2; never cancel the foreground service. */
    fun cancelAllIncomingSms(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NotificationIds.TEST_SMS)
        val active = try {
            manager.activeNotifications
        } catch (_: RuntimeException) {
            emptyArray()
        }
        active.asSequence()
            .filter { it.notification.channelId == CHANNEL_INCOMING_SMS }
            .filter { it.id != SERVICE_NOTIFICATION_ID }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    fun showRestoreNotification(context: Context) {
        if (!canNotify(context)) return
        createChannels(context)
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_RESTORE_SERVICE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            RESTORE_OPEN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("恢复 SMS-L")
            .setContentText("系统未允许自动恢复，点击重新启动短信接收服务。")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(RESTORE_NOTIFICATION_ID, notification)
    }

    private const val SERVICE_OPEN_REQUEST_CODE = 10
    private const val SERVICE_STOP_REQUEST_CODE = 11
    private const val RESTORE_OPEN_REQUEST_CODE = 12
}
