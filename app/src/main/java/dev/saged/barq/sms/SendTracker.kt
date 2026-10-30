package dev.saged.barq.sms

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Lets the sender wait until the radio confirms a message ("maximum speed" mode). */
@Singleton
class SendTracker @Inject constructor() {
    private val waiters = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()

    fun register(messageId: Long): CompletableDeferred<Boolean> =
        CompletableDeferred<Boolean>().also { waiters[messageId] = it }

    fun complete(messageId: Long, success: Boolean) {
        waiters.remove(messageId)?.complete(success)
    }

    /** Stops waiting for this message. The waiter is never cancelled (nobody awaits a cancelled one). */
    fun forget(messageId: Long) {
        waiters.remove(messageId)
    }
}

/** Shared state of bulk sending, read by the engine, the drainer, the service and the UI. */
@Singleton
class BulkState @Inject constructor() {
    /** True while a bulk send is still being written into the queue. */
    val preparing = MutableStateFlow(false)

    /** Batch the user paused (0 = nothing paused). */
    private val _paused = MutableStateFlow(0L)
    val paused: StateFlow<Long> = _paused

    fun pause(batchId: Long) {
        _paused.value = batchId
    }

    fun resume() {
        _paused.value = 0L
    }

    /** Waiting for Android's per-minute limit until this time (0 = not waiting). */
    val waitingLimitUntil = MutableStateFlow(0L)

    /** The radio stopped answering (Android may be showing its "allow many messages?" question). */
    val stalled = MutableStateFlow(false)
}
