package com.localsmsrelay.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SmsMessageDao {
    @Insert
    long insert(SmsMessageEntity message);

    @Query("SELECT * FROM sms_messages ORDER BY receivedAt DESC, id DESC")
    List<SmsMessageEntity> getAllNewestFirst();

    @Query("DELETE FROM sms_messages")
    void clearAll();

    @Query("DELETE FROM sms_messages WHERE id NOT IN " +
            "(SELECT id FROM sms_messages ORDER BY receivedAt DESC, id DESC LIMIT :maximum)")
    void trimToNewest(int maximum);
}
