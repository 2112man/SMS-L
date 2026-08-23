package com.localsmsrelay

class RecentMessageIds(
    private val capacity: Int = 100,
    private val windowMillis: Long = 10 * 60 * 1000L
) {
    private val entries = LinkedHashMap<String, Long>(capacity, 0.75f, true)

    @Synchronized
    fun isDuplicateAndRemember(messageId: String, now: Long = System.currentTimeMillis()): Boolean {
        entries.entries.removeAll { now - it.value > windowMillis }
        val previous = entries[messageId]
        entries[messageId] = now
        while (entries.size > capacity) {
            val oldest = entries.entries.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        return previous != null && now - previous <= windowMillis
    }
}

