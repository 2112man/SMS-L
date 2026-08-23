package com.localsmsrelay.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsDatabaseInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "sms-history-instrumented-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun emptyInsertNewestFirstTrimAndClear() {
        val database = openDatabase()
        val dao = database.smsMessageDao()
        assertTrue(dao.getAllNewestFirst().isEmpty())

        database.runInTransaction {
            for (index in 1..501) {
                dao.insert(entity(text = "短信$index", receivedAt = index.toLong()))
            }
            dao.trimToNewest(SmsHistoryRepository.MAX_HISTORY)
        }

        val messages = dao.getAllNewestFirst()
        assertEquals(500, messages.size)
        assertEquals("短信501", messages.first().text)
        assertEquals("短信2", messages.last().text)

        dao.clearAll()
        assertTrue(dao.getAllNewestFirst().isEmpty())
        database.close()
    }

    @Test
    fun fileDatabaseSurvivesCloseAndReopen() {
        openDatabase().also { database ->
            database.smsMessageDao().insert(
                entity(
                    text = "【测试银行】您的验证码为583921，5分钟内有效",
                    receivedAt = 1_777_000_000_000L,
                    otp = "583921",
                    messageId = "persist-1"
                )
            )
            database.close()
        }

        openDatabase().also { reopened ->
            val stored = reopened.smsMessageDao().getAllNewestFirst().single()
            assertEquals("【测试银行】您的验证码为583921，5分钟内有效", stored.text)
            assertEquals("583921", stored.otp)
            assertEquals("persist-1", stored.messageId)
            reopened.close()
        }
    }

    private fun openDatabase(): SmsDatabase = Room.databaseBuilder(
        context,
        SmsDatabase::class.java,
        databaseName
    ).allowMainThreadQueries().build()

    private fun entity(
        text: String,
        receivedAt: Long,
        otp: String? = null,
        messageId: String? = null
    ) = SmsMessageEntity().apply {
        this.text = text
        this.receivedAt = receivedAt
        this.otp = otp
        this.messageId = messageId
    }
}
