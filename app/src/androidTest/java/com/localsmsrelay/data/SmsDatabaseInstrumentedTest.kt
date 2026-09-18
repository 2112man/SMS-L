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

    /**
     * v2 时代的去重是纯内存的（RecentMessageIds），进程重启即失效，
     * 因此历史数据里可能真的存在重复 messageId。迁移必须先删重复再建唯一索引，
     * 否则 CREATE UNIQUE INDEX 会直接失败、整个升级流程炸掉。
     */
    @Test
    fun migrationFromVersion2DeduplicatesMessageIdsAndKeepsNullIds() {
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
                    messageId TEXT,
                    isRead INTEGER NOT NULL DEFAULT 0,
                    notificationId INTEGER
                )""".trimIndent()
            )
            // 三条重复 messageId，保留 id 最大的那条
            legacy.execSQL("INSERT INTO sms_messages(text, receivedAt, messageId) VALUES('重复一', 1, 'dup-1')")
            legacy.execSQL("INSERT INTO sms_messages(text, receivedAt, messageId) VALUES('重复二', 2, 'dup-1')")
            legacy.execSQL("INSERT INTO sms_messages(text, receivedAt, messageId) VALUES('重复三', 3, 'dup-1')")
            legacy.execSQL("INSERT INTO sms_messages(text, receivedAt, messageId) VALUES('唯一', 4, 'unique-1')")
            // 两条没有 messageId 的历史记录：SQLite 唯一索引允许多个 NULL，必须都保留
            legacy.execSQL("INSERT INTO sms_messages(text, receivedAt, messageId) VALUES('无ID甲', 5, NULL)")
            legacy.execSQL("INSERT INTO sms_messages(text, receivedAt, messageId) VALUES('无ID乙', 6, NULL)")
            legacy.version = 2
            legacy.close()
        }

        openDatabase().also { migrated ->
            val dao = migrated.smsMessageDao()
            val stored = dao.getAllNewestFirst()

            // 6 条中 3 条重复合并为 1 条，共剩 4 条
            assertEquals(4, stored.size)
            assertEquals(1, stored.count { it.messageId == "dup-1" })
            assertEquals("重复三", stored.first { it.messageId == "dup-1" }.text)
            assertEquals(2, stored.count { it.messageId == null })

            // 唯一索引由迁移创建，重复插入必须被忽略
            val duplicate = entity(text = "再来一条重复", receivedAt = 7, messageId = "dup-1")
            assertEquals(-1L, dao.insertIgnoringDuplicate(duplicate))
            assertEquals(4, dao.getAllNewestFirst().size)

            // 新 messageId 正常写入
            val fresh = entity(text = "新的", receivedAt = 8, messageId = "fresh-1")
            assertTrue(dao.insertIgnoringDuplicate(fresh) > 0L)
            assertEquals(5, dao.getAllNewestFirst().size)

            // 无 messageId 的记录不受唯一索引限制，可以继续累加
            assertTrue(dao.insertIgnoringDuplicate(entity(text = "无ID丙", receivedAt = 9)) > 0L)
            migrated.close()
        }
    }

    private fun openDatabase(): SmsDatabase = Room.databaseBuilder(
        context,
        SmsDatabase::class.java,
        databaseName
    ).addMigrations(SmsDatabase.MIGRATION_1_2, SmsDatabase.MIGRATION_2_3)
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
