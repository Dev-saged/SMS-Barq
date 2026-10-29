package dev.saged.barq.sms

import dev.saged.barq.data.OutgoingRepository
import dev.saged.barq.data.db.Box
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.db.SendError
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.util.CrashLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only place that hands messages to the radio. Both the background worker (single messages)
 * and the foreground service (bulk sends) call [drain]; a lock guarantees one drainer at a time,
 * so a message can never be sent twice.
 */
@Singleton
class SendDrainer @Inject constructor(
    private val messages: MessageDao,
    private val outgoing: OutgoingRepository,
    private val sender: SmsSender,
    private val settings: SettingsRepository,
    private val tracker: SendTracker,
    private val state: BulkState,
    private val queue: SendQueue,
) {
    enum class Result { DONE, BUSY, TIME_UP }

    private val mutex = Mutex()

    val isBusy: Boolean get() = mutex.isLocked

    /**
     * Sends everything that is due, one message at a time. Returns [Result.TIME_UP] after
     * [maxMillis] so a worker can hand over to a fresh one.
     */
    suspend fun drain(maxMillis: Long = Long.MAX_VALUE, stopWhen: () -> Boolean = { false }): Result {
        if (!mutex.tryLock()) return Result.BUSY
        val started = System.currentTimeMillis()
        var result = Result.DONE
        try {
            loop@ while (true) {
                val s = settings.current()
                val paused = state.paused.value
                val batch = messages.dueQueued(System.currentTimeMillis(), BATCH, paused)
                if (batch.isEmpty()) break
                for (queued in batch) {
                    if (stopWhen()) break@loop
                    if (state.paused.value != 0L && queued.batchId == state.paused.value) continue
                    // It may have been cancelled or deleted since the batch was read.
                    val fresh = messages.get(queued.id) ?: continue
                    if (fresh.box != Box.QUEUED) continue
                    sendOne(fresh, s.deliveryReports, s.sendDelayMs)
                    if (s.sendDelayMs > 0) delay(s.sendDelayMs)
                    if (System.currentTimeMillis() - started > maxMillis) {
                        result = Result.TIME_UP
                        break@loop
                    }
                }
            }
        } finally {
            mutex.unlock()
        }
        // Something arrived while we were finishing: make sure it is not left behind.
        if (result == Result.DONE &&
            messages.dueQueued(System.currentTimeMillis(), 1, state.paused.value).isNotEmpty()
        ) {
            queue.kick()
        }
        return result
    }

    private suspend fun sendOne(message: dev.saged.barq.data.db.MessageEntity, deliveryReports: Boolean, delayMs: Long) {
        val outbox = outgoing.markOutbox(message)
        // "Maximum speed": no fixed pause, the next message goes as soon as the radio confirms.
        val waiter = if (delayMs == 0L) tracker.register(outbox.id) else null
        val handed = runCatching { sender.send(outbox, deliveryReports) }
        if (handed.isFailure) {
            tracker.forget(outbox.id)
            handed.exceptionOrNull()?.let { CrashLog.warn("send", it) }
            outgoing.onSendResult(outbox.id, false, SendError.REJECTED)
            return
        }
        if (waiter != null) withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { waiter.await() }
    }

    private companion object {
        const val BATCH = 50
        const val CONFIRM_TIMEOUT_MS = 20_000L
    }
}
