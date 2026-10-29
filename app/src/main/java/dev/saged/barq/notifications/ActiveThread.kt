package dev.saged.barq.notifications

/**
 * The conversation currently on screen (set by the conversation screen while it is resumed).
 * A message arriving there is marked read instead of ringing a notification.
 */
object ActiveThread {
    @Volatile private var threadId: Long = -1

    fun set(id: Long) {
        threadId = id
    }

    fun clear(id: Long) {
        if (threadId == id) threadId = -1
    }

    fun isOpen(id: Long): Boolean = threadId == id && id >= 0
}
