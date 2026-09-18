package com.localsmsrelay.data;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 唯一索引建立在 messageId 上，是"断线重放不产生重复通知"的最终保障。
 *
 * SQLite 的唯一索引把多个 NULL 视为互不相同，因此 messageId 缺失的历史记录
 * 不会互相冲突，这一点正好符合预期。
 */
@Entity(
        tableName = "sms_messages",
        indices = {@Index(value = {"messageId"}, unique = true, name = "index_sms_messages_messageId")}
)
public class SmsMessageEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @Nullable
    public String sender;

    @NonNull
    public String text = "";

    @Nullable
    public String otp;

    public long receivedAt;

    @Nullable
    public String messageId;

    @ColumnInfo(defaultValue = "0")
    public boolean isRead = false;

    @Nullable
    public Integer notificationId;
}
