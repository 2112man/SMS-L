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
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.concurrent.atomic.AtomicInteger

object NotificationHelper {
    private const val CHANNEL_SERVICE = "relay_service"
    private const val CHANNEL_SMS_VIBRATE = "relay_sms_vibrate"
    private const val CHANNEL_SMS_SILENT = "relay_sms_silent"
    private const val CHANNEL_RESTORE = "relay_restore"
    const val SERVICE_NOTIFICATION_ID = 1001
    const val RESTORE_NOTIFICATION_ID = 1002
    private val nextSmsId = AtomicInteger(2000)

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val service = NotificationChannel(
            CHANNEL_SERVICE,
            "SMS-L 后台服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示局域网短信监听服务的运行状态"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val vibrate = NotificationChannel(
            CHANNEL_SMS_VIBRATE,
            "iPhone 短信（震动）",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "收到 iPhone 转发的短信时显示并震动"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 120, 250)
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audio)
        }
        val silent = NotificationChannel(
            CHANNEL_SMS_SILENT,
            "iPhone 短信（不震动）",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "收到 iPhone 转发的短信时显示但不震动"
            enableVibration(false)
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audio)
        }
        val restore = NotificationChannel(
            CHANNEL_RESTORE,
            "服务恢复提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "开机后需要手动恢复监听服务时提醒"
        }

        manager.createNotificationChannels(listOf(service, vibrate, silent, restore))
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun serviceNotification(context: Context, status: String): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            context,
            11,
            Intent(context, RelayService::class.java).setAction(RelayService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SMS-L")
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "停止服务", stop).build())
            .build()
    }

    fun showSms(context: Context, sender: String?, text: String, otp: String?, vibrate: Boolean) {
        if (!canNotify(context)) return
        createChannels(context)
        val id = nextSmsId.getAndIncrement()
        val content = NotificationContentFormatter.format(sender, text, otp)
        val openApp = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(
            context,
            if (vibrate) CHANNEL_SMS_VIBRATE else CHANNEL_SMS_SILENT
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(Notification.BigTextStyle().bigText(content.text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)

        if (!otp.isNullOrBlank()) {
            val copyIntent = Intent(context, CopyOtpReceiver::class.java).putExtra(CopyOtpReceiver.EXTRA_OTP, otp)
            val copy = PendingIntent.getBroadcast(
                context,
                id,
                copyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(Notification.Action.Builder(null, "复制 $otp", copy).build())
        }
        context.getSystemService(NotificationManager::class.java).notify(id, builder.build())
    }

    fun showRestoreNotification(context: Context) {
        if (!canNotify(context)) return
        createChannels(context)
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_RESTORE_SERVICE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            12,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_RESTORE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("恢复 SMS-L")
            .setContentText("系统未允许自动恢复，点击重新启动局域网监听。")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(RESTORE_NOTIFICATION_ID, notification)
    }
}
