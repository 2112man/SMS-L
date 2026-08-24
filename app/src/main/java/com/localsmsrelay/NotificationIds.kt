package com.localsmsrelay

object NotificationIds {
    const val SERVICE = 1001
    const val RESTORE_SERVICE = 1002
    const val TEST_SMS = 1900

    private const val SMS_BASE = 2000
    private const val SMS_RANGE = 900_000_000L
    private const val COPY_REQUEST_OFFSET = 1_000_000_000

    /** Room AUTOINCREMENT ids remain unique across process restarts and history clearing. */
    fun incomingSms(databaseId: Long): Int {
        require(databaseId > 0) { "databaseId must be positive" }
        return SMS_BASE + ((databaseId - 1L) % SMS_RANGE).toInt()
    }

    fun contentRequestCode(notificationId: Int): Int = notificationId

    fun copyRequestCode(notificationId: Int): Int = notificationId + COPY_REQUEST_OFFSET
}
