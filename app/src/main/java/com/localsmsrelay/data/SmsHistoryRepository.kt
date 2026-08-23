package com.localsmsrelay.data

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.localsmsrelay.IncomingMessage
import java.util.concurrent.Executors

class SmsHistoryRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val database = SmsDatabase.getInstance(appContext)
    private val dao = database.smsMessageDao()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called from NanoHTTPD's request thread and completes before notification delivery. */
    fun saveIncoming(message: IncomingMessage, otp: String?, receivedAt: Long) {
        val entity = SmsMessageEntity().apply {
            sender = message.sender
            text = message.text
            this.otp = otp
            this.receivedAt = receivedAt
            messageId = message.messageId
        }
        database.runInTransaction {
            entity.id = dao.insert(entity)
            dao.trimToNewest(MAX_HISTORY)
        }
        notifyHistoryChanged()
    }

    fun loadAll(callback: (List<SmsMessageEntity>) -> Unit) {
        ioExecutor.execute {
            val messages = dao.getAllNewestFirst()
            mainHandler.post { callback(messages) }
        }
    }

    fun clearAll(callback: (() -> Unit)? = null) {
        ioExecutor.execute {
            dao.clearAll()
            notifyHistoryChanged()
            mainHandler.post { callback?.invoke() }
        }
    }

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
