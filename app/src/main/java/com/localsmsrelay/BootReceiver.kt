package com.localsmsrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AppPrefs.autoStart(context) || !AppPrefs.serviceEnabled(context)) return

        val service = Intent(context, RelayService::class.java).setAction(RelayService.ACTION_START)
        try {
            context.startForegroundService(service)
        } catch (_: RuntimeException) {
            NotificationHelper.showRestoreNotification(context)
        } catch (_: SecurityException) {
            NotificationHelper.showRestoreNotification(context)
        }
    }
}
