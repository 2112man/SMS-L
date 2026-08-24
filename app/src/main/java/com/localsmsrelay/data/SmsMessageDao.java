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

    @Query("UPDATE sms_messages SET notificationId = :notificationId WHERE id = :messageId")
    void attachNotificationId(long messageId, int notificationId);

    @Query("UPDATE sms_messages SET isRead = 1 WHERE id = :messageId")
    int markRead(long messageId);

    @Query("UPDATE sms_messages SET isRead = 1 WHERE isRead = 0")
    int markAllRead();

    @Query("SELECT COUNT(*) FROM sms_messages WHERE isRead = 0")
    int countUnread();

    @Query("DELETE FROM sms_messages")
    void clearAll();

    @Query("DELETE FROM sms_messages WHERE id NOT IN " +
            "(SELECT id FROM sms_messages ORDER BY receivedAt DESC, id DESC LIMIT :maximum)")
    void trimToNewest(int maximum);
}
