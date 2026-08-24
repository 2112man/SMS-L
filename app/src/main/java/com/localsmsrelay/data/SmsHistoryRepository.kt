package com.localsmsrelay.data

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.localsmsrelay.IncomingMessage
import com.localsmsrelay.NotificationIds
import java.util.concurrent.Executors

data class SavedIncomingMessage(
    val databaseId: Long,
    val notificationId: Int,
    val unreadCount: Int
)

class SmsHistoryRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val database = SmsDatabase.getInstance(appContext)
    private val dao = database.smsMessageDao()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called from NanoHTTPD's request thread and completes before notification delivery. */
    fun saveIncoming(message: IncomingMessage, otp: String?, receivedAt: Long): SavedIncomingMessage {
        val entity = SmsMessageEntity().apply {
            sender = message.sender
            text = message.text
            this.otp = otp
            this.receivedAt = receivedAt
            messageId = message.messageId
            isRead = false
        }
        val saved = database.runInTransaction<SavedIncomingMessage> {
            entity.id = dao.insert(entity)
            val notificationId = NotificationIds.incomingSms(entity.id)
            dao.attachNotificationId(entity.id, notificationId)
            dao.trimToNewest(MAX_HISTORY)
            SavedIncomingMessage(
                databaseId = entity.id,
                notificationId = notificationId,
                unreadCount = dao.countUnread()
            )
        }
        notifyHistoryChanged()
        return saved
    }

    fun loadAll(callback: (List<SmsMessageEntity>) -> Unit) {
        ioExecutor.execute {
            val messages = dao.getAllNewestFirst()
            mainHandler.post { callback(messages) }
        }
    }

    fun clearAll(callback: ((List<Int>) -> Unit)? = null) {
        ioExecutor.execute {
            val notificationIds = dao.getAllNewestFirst().map(::notificationIdFor)
            dao.clearAll()
            notifyHistoryChanged()
            mainHandler.post { callback?.invoke(notificationIds) }
        }
    }

    fun markRead(messageId: Long, callback: (() -> Unit)? = null) {
        ioExecutor.execute {
            if (dao.markRead(messageId) > 0) notifyHistoryChanged()
            mainHandler.post { callback?.invoke() }
        }
    }

    fun markAllRead(callback: ((List<Int>) -> Unit)? = null) {
        ioExecutor.execute {
            val notificationIds = dao.getAllNewestFirst().map(::notificationIdFor)
            if (dao.markAllRead() > 0) notifyHistoryChanged()
            mainHandler.post { callback?.invoke(notificationIds) }
        }
    }

    private fun notificationIdFor(message: SmsMessageEntity): Int =
        message.notificationId ?: NotificationIds.incomingSms(message.id)

    private fun notifyHistoryChanged() {
        appContext.sendBroadcast(
            Intent(ACTION_HISTORY_CHANGED).setPackage(appContext.packageName)
        )
    }

    companion object {
        const val MAX_HISTORY = 500
        const val ACTION_HISTORY_CHANGED = "com.localsmsrelay.HISTORY_CHANGED"

        @Volatile
        private var instance: SmsHistoryRepository? = null

        fun get(context: Context): SmsHistoryRepository =
            instance ?: synchronized(this) {
                instance ?: SmsHistoryRepository(context).also { instance = it }
            }
    }
}
