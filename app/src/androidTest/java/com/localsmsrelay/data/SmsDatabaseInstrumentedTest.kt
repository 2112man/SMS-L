package com.localsmsrelay.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        val newest = messages.first()
        assertFalse(newest.isRead)
        assertNull(newest.notificationId)
        val notificationId = 2000
        dao.attachNotificationId(newest.id, notificationId)
        assertEquals(500, dao.countUnread())
        assertEquals(1, dao.markRead(newest.id))
        assertEquals(499, dao.countUnread())
        assertEquals(notificationId, dao.getAllNewestFirst().first().notificationId)
        assertEquals(499, dao.markAllRead())
        assertEquals(0, dao.countUnread())

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

    @Test
    fun migrationFromVersion1PreservesHistoryAndAddsReadMetadata() {
        context.deleteDatabase(databaseName)
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openDatabase(
            databaseFile.path,
            null,
            SQLiteDatabase.CREATE_IF_NECESSARY
        ).also { legacy ->
            legacy.execSQL(
                """CREATE TABLE IF NOT EXISTS sms_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sender TEXT,
                    text TEXT NOT NULL,
                    otp TEXT,
                    receivedAt INTEGER NOT NULL,
                    messageId TEXT
                )""".trimIndent()
            )
            legacy.execSQL(
                "INSERT INTO sms_messages(sender, text, otp, receivedAt, messageId) " +
                    "VALUES('95555', '【测试银行】验证码583921', '583921', 1777000000000, 'legacy-1')"
            )
            legacy.version = 1
            legacy.close()
        }

        openDatabase().also { migrated ->
            val stored = migrated.smsMessageDao().getAllNewestFirst().single()
            assertEquals("【测试银行】验证码583921", stored.text)
            assertEquals("legacy-1", stored.messageId)
            assertFalse(stored.isRead)
            assertNull(stored.notificationId)
            migrated.close()
        }
    }

    private fun openDatabase(): SmsDatabase = Room.databaseBuilder(
        context,
        SmsDatabase::class.java,
        databaseName
    ).addMigrations(SmsDatabase.MIGRATION_1_2)
        .allowMainThreadQueries()
        .build()

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
