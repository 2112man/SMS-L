package com.localsmsrelay.data;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sms_messages")
public class SmsMessageEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @Nullable
    public String sender;

    public String text = "";

    @Nullable
    public String otp;

    public long receivedAt;

    @Nullable
    public String messageId;
}
