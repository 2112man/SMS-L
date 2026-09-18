package com.localsmsrelay.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {SmsMessageEntity.class}, version = 3, exportSchema = false)
public abstract class SmsDatabase extends RoomDatabase {
    public static final String DATABASE_NAME = "local_sms_relay.db";

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE sms_messages ADD COLUMN isRead INTEGER NOT NULL DEFAULT 0"
            );
            database.execSQL(
                    "ALTER TABLE sms_messages ADD COLUMN notificationId INTEGER"
            );
        }
    };

    /**
     * v2 -> v3：给 messageId 加唯一索引，实现跨进程重启的持久化去重。
     *
     * 必须先删除历史重复再建索引：SQLite 在表内已存在重复值时创建唯一索引会直接失败，
     * 而 v2 时代的去重是纯内存的（RecentMessageIds），进程重启即失效，
     * 所以历史数据里很可能真的存在重复。
     */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "DELETE FROM sms_messages "
                            + "WHERE messageId IS NOT NULL AND id NOT IN "
                            + "(SELECT MAX(id) FROM sms_messages "
                            + "WHERE messageId IS NOT NULL GROUP BY messageId)"
            );
            database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_sms_messages_messageId "
                            + "ON sms_messages (messageId)"
            );
        }
    };

    private static volatile SmsDatabase instance;

    public abstract SmsMessageDao smsMessageDao();

    public static SmsDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (SmsDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            SmsDatabase.class,
                            DATABASE_NAME
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build();
                }
            }
        }
        return instance;
    }
}
