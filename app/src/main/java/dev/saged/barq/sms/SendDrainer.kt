package dev.saged.barq.sms

import android.telephony.SmsMessage
import dev.saged.barq.data.OutgoingRepository
import dev.saged.barq.data.db.Box
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.db.SendError
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.util.CrashLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only place that hands messages to the radio. Both the background worker (single messages)
 * and the foreground service (bulk sends) call [drain]; a lock guarantees one drainer at a time,
 * so a message can never be sent twice.
 *
 * How it stays fast and never gets stuck:
 *  - a small window of messages waits for the radio's answer at the same time (the radio always
 *    has the next one ready), instead of one-by-one round trips;
 *  - it paces itself under Android's per-minute limit, so Android never stops the queue to ask;
 *  - a message typed by hand goes before the rest of a bulk send;
 *  - a radio that stops answering is noticed (not waited for forever) and shown to the user;
 *  - temporary failures come back by themselves (see [OutgoingRepository.onSendResult]).
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
    private val limit: SendLimit,
) {
    enum class Result { DONE, BUSY, TIME_UP }

    private class Flight(val id: Long, val waiter: CompletableDeferred<Boolean>, val at: Long)

    /** How many messages wait for the radio at once, and the pause between two messages. */
    private class Speed(val window: Int, val gapMs: Long) {
        companion object {
            fun of(delayMs: Long) = when {
                delayMs <= 0L -> Speed(6, 0)
                delayMs <= 100L -> Speed(3, 100)
                delayMs <= 250L -> Speed(2, 250)
                else -> Speed(1, delayMs)
            }
        }
    }

    private val mutex = Mutex()
    private val auto = AutoSpeed()

    /** SMS parts handed to the radio recently: (time, parts). Used for Android's limit. */
    private val recent = ArrayDeque<Pair<Long, Int>>()

    val isBusy: Boolean get() = mutex.isLocked

    /**
     * Sends everything that is due. Returns [Result.TIME_UP] after [maxMillis] so a worker can
     * hand over to a fresh one.
     */
    suspend fun drain(maxMillis: Long = Long.MAX_VALUE, stopWhen: () -> Boolean = { false }): Result {
        if (!mutex.tryLock()) return Result.BUSY
        val started = System.currentTimeMillis()
        var result = Result.DONE
        val inFlight = ArrayList<Flight>()
        try {
            loop@ while (true) {
                val s = settings.current()
                val speed = if (s.sendDelayMs < 0) auto.pace().let { Speed(it.window, it.gapMs) } else Speed.of(s.sendDelayMs)
                val androidLimit = if (s.respectLimit) limit.current() else null
                val now = System.currentTimeMillis()
                val batch = messages.dueQueued(now, BATCH, state.paused.value)
                if (batch.isEmpty()) break
                for (queued in batch) {
                    if (stopWhen()) break@loop
                    if (state.paused.value != 0L && queued.batchId == state.paused.value) continue
                    // It may have been cancelled, sent or deleted since the batch was read.
                    val fresh = messages.get(queued.id) ?: continue
                    if (fresh.box != Box.QUEUED || fresh.retryAt > System.currentTimeMillis()) continue
                    waitForSlot(inFlight, speed.window)
                    val parts = partsOf(fresh)
                    if (androidLimit != null && !androidLimit.raised) waitForLimit(parts, androidLimit)
                    if (stopWhen()) break@loop
                    sendOne(fresh, s.deliveryReports, inFlight)
                    if (speed.gapMs > 0) delay(speed.gapMs)
                    if (System.currentTimeMillis() - started > maxMillis) {
                        result = Result.TIME_UP
                        break@loop
                    }
                    // A message typed by hand meanwhile goes before the rest of the bulk send.
                    if (queued.batchId != 0L) {
                        val next = messages.dueQueued(System.currentTimeMillis(), 1, state.paused.value).firstOrNull()
                        if (next != null && next.batchId == 0L) continue@loop
                    }
                }
            }
            // Stay until the last messages are confirmed, so their result is never lost.
            withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { inFlight.forEach { it.waiter.await() } }
        } finally {
            inFlight.forEach { tracker.forget(it.id) }
            state.waitingLimitUntil.value = 0L
            state.stalled.value = false
            mutex.unlock()
        }
        val now = System.currentTimeMillis()
        // Something arrived while we were finishing: make sure it is not left behind.
        if (result == Result.DONE && messages.dueQueued(now, 1, state.paused.value).isNotEmpty()) {
            queue.kick()
        }
        // Automatic retries waiting for their time wake the queue up by themselves.
        messages.nextRetryAt(now)?.let { queue.kickAt(it) }
        return result
    }

    /** Waits until fewer than [window] messages are waiting for the radio. */
    private suspend fun waitForSlot(inFlight: MutableList<Flight>, window: Int) {
        inFlight.removeAll { it.waiter.isCompleted }
        if (inFlight.none { System.currentTimeMillis() - it.at > STALL_MS }) state.stalled.value = false
        while (inFlight.size >= window) {
            val oldest = inFlight.first()
            val left = CONFIRM_TIMEOUT_MS - (System.currentTimeMillis() - oldest.at)
            if (left <= 0) {
                // No answer at all: the message stays "sending" and its answer is still recorded
                // when it comes. The queue moves on instead of waiting forever.
                state.stalled.value = true
                auto.onStall()
                inFlight.remove(oldest)
                tracker.forget(oldest.id)
                continue
            }
            withTimeoutOrNull(left.coerceAtMost(STALL_MS)) { oldest.waiter.await() }
            if (!oldest.waiter.isCompleted && System.currentTimeMillis() - oldest.at > STALL_MS) {
                state.stalled.value = true
                auto.onStall()
            }
            inFlight.removeAll { it.waiter.isCompleted }
            if (inFlight.none { System.currentTimeMillis() - it.at > STALL_MS }) state.stalled.value = false
        }
    }

    /** Keeps the app under Android's "N messages per interval" so Android never stops the queue. */
    private suspend fun waitForLimit(parts: Int, l: SendLimit.Limit) {
        val allowed = (l.maxCount - SAFETY_MARGIN).coerceAtLeast(1)
        while (true) {
            val now = System.currentTimeMillis()
            while (recent.isNotEmpty() && now - recent.first().first >= l.intervalMs) recent.removeFirst()
            val used = recent.sumOf { it.second }
            if (used + parts <= allowed || recent.isEmpty()) {
                state.waitingLimitUntil.value = 0L
                recent.addLast(now to parts)
                return
            }
            val until = recent.first().first + l.intervalMs + 250
            state.waitingLimitUntil.value = until
            delay((until - now).coerceIn(250L, l.intervalMs))
        }
    }

    private fun partsOf(message: MessageEntity): Int =
        runCatching { SmsMessage.calculateLength(message.body, false)[0] }.getOrDefault(1).coerceAtLeast(1)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun sendOne(message: MessageEntity, deliveryReports: Boolean, inFlight: MutableList<Flight>) {
        val outbox = outgoing.markOutbox(message)
        val waiter = tracker.register(outbox.id)
        val handed = runCatching { sender.send(outbox, deliveryReports) }
        if (handed.isFailure) {
            tracker.forget(outbox.id)
            handed.exceptionOrNull()?.let { CrashLog.warn("send", it) }
            outgoing.onSendResult(outbox.id, false, SendError.REJECTED)
            return
        }
        val at = System.currentTimeMillis()
        waiter.invokeOnCompletion { e -> if (e == null) auto.onResult(System.currentTimeMillis() - at, waiter.getCompleted()) }
        inFlight += Flight(outbox.id, waiter, at)
    }

    private companion object {
        const val BATCH = 25
        const val CONFIRM_TIMEOUT_MS = 45_000L
        /** No answer for this long = probably Android's "allow?" question is on screen. */
        const val STALL_MS = 12_000L
        /** Stay a little under Android's number, it also counts other sends we cannot see. */
        const val SAFETY_MARGIN = 2
    }
}
