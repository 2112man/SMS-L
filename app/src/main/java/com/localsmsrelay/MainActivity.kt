package com.localsmsrelay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.localsmsrelay.data.SmsHistoryRepository
import com.localsmsrelay.ui.MainScreen
import com.localsmsrelay.ui.SmsTheme
import com.localsmsrelay.ui.rememberRelayUiState

/**
 * 界面宿主。
 *
 * 只做四件事：初始化通知渠道、预热两个 Token、处理通知点击、把 Compose 界面挂上去。
 * 业务逻辑全在 RelayService / RelayUiState 里，这里保持尽量薄。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        NotificationHelper.createChannels(this)
        // 首次启动就生成两个 Token，保证设置页一打开就有值可以复制。
        AppPrefs.token(this)
        AppPrefs.androidToken(this)

        handleNotificationTap(intent)

        setContent {
            SmsTheme {
                val state = rememberRelayUiState()
                MainScreen(state)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationTap(intent)
    }

    /**
     * 点击某条短信通知时，只标记那一条为已读并取消它的通知，
     * 绝不动前台服务的那条常驻通知。
     */
    private fun handleNotificationTap(intent: Intent?) {
        val messageId = intent?.getLongExtra(EXTRA_MESSAGE_ID, -1L) ?: -1L
        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, -1) ?: -1
        val isIncomingSmsTap = messageId > 0L && notificationId > 0 &&
            notificationId != NotificationHelper.SERVICE_NOTIFICATION_ID
        if (!isIncomingSmsTap) return

        NotificationHelper.cancelIncomingSms(this, notificationId)
        SmsHistoryRepository.get(this).markRead(messageId)
    }

    companion object {
        // 这些常量被 NotificationHelper 用来构造 PendingIntent，不能删。
        const val EXTRA_RESTORE_SERVICE = "restore_service"
        const val EXTRA_OPEN_MESSAGES = "open_messages"
        const val EXTRA_MESSAGE_ID = "message_database_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
