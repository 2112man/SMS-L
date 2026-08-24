package com.localsmsrelay.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {SmsMessageEntity.class}, version = 2, exportSchema = false)
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
                    ).addMigrations(MIGRATION_1_2).build();
                }
            }
        }
        return instance;
    }
}
