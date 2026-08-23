package com.localsmsrelay.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {SmsMessageEntity.class}, version = 1, exportSchema = false)
public abstract class SmsDatabase extends RoomDatabase {
    public static final String DATABASE_NAME = "local_sms_relay.db";

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
                    ).build();
                }
            }
        }
        return instance;
    }
}
