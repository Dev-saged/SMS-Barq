package dev.saged.barq.sms

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.data.OutgoingRepository
import dev.saged.barq.data.db.BatchCounts
import dev.saged.barq.data.db.BatchRow
import dev.saged.barq.data.db.ConversationDao
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.sim.SimRepository
import dev.saged.barq.di.AppScope
import dev.saged.barq.domain.PhoneUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for sending. Every path — a conversation, a new message, a bulk send,
 * a reply from a notification or the call screen, an auto-reply, a scheduled message, a resend —
 * comes here, so the SIM choice, the queue and the progress are always handled the same way.
 */
@Singleton
class SendEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outgoing: OutgoingRepository,
    private val conversations: ConversationDao,
    private val messages: MessageDao,
    private val settings: SettingsRepository,
    private val sims: SimRepository,
    private val scheduler: ScheduleManager,
    private val queue: SendQueue,
    private val state: BulkState,
    @AppScope private val scope: CoroutineScope,
) {
    sealed interface Outcome {
        data class Single(val threadId: Long) : Outcome
        data class Bulk(val batchId: Long, val count: Int) : Outcome
        data object Empty : Outcome
    }

    val preparing get() = state.preparing
    val paused get() = state.paused
    val waitingLimitUntil get() = state.waitingLimitUntil
    val stalled get() = state.stalled

    /**
     * The SIM to send from: the one the user picked, else the conversation's SIM, else Barq's
     * default SIM, else the phone's default (-1). A SIM that is no longer in the phone is skipped.
     */
    suspend fun resolveSubId(threadId: Long?, requested: Int?): Int {
        val active = sims.activeSims().map { it.subId }
        fun usable(id: Int?): Boolean = id != null && id >= 0 && (active.isEmpty() || id in active)
        if (usable(requested)) return requested ?: -1
        if (threadId != null) {
            val conv = conversations.get(threadId)
            if (conv != null && usable(conv.subId)) return conv.subId
        }
        val def = settings.current().defaultSubId
        if (usable(def)) return def
        return -1
    }

    /** A message typed in a conversation (or a reply from its notification when [subId] is null). */
    suspend fun sendToThread(threadId: Long, body: String, subId: Int?, scheduledAt: Long = 0): Boolean {
        val conv = conversations.get(threadId) ?: return false
        val recipients = PhoneUtils.splitAddresses(conv.addresses)
        val sub = resolveSubId(threadId, subId)
        val queued = outgoing.enqueue(recipients, body, sub, scheduledAt, autoReply = false, batchId = 0, clearDraft = true)
        afterQueue(queued)
        return queued.count > 0
    }

    /** A reaction sent as a short SMS: it is attached to [targetId] instead of showing as a bubble. */
    suspend fun sendReaction(threadId: Long, body: String, targetId: Long) {
        val conv = conversations.get(threadId) ?: return
        val recipients = PhoneUtils.splitAddresses(conv.addresses)
        val sub = resolveSubId(threadId, null)
        afterQueue(outgoing.enqueue(recipients, body, sub, 0, autoReply = false, batchId = 0, clearDraft = false, reactionTo = targetId))
    }

    /**
     * One message from Barq's local gateway (a bot, a script…). It joins [batchId] so the burst is
     * followed like a bulk send; nothing is sent here — the gateway service drains the queue.
     */
    suspend fun enqueueGateway(address: String, body: String, batchId: Long): Boolean {
        val sub = resolveSubId(null, null)
        return outgoing.enqueue(listOf(address), body, sub, 0, autoReply = false, batchId = batchId, clearDraft = false).count > 0
    }

    /** Counts for one batch right now (the gateway's web panel asks for them). */
    suspend fun batchCounts(batchId: Long): BatchCounts = messages.batchCounts(batchId)

    suspend fun sendAutoReply(address: String, body: String, subId: Int) {
        val sub = resolveSubId(null, subId)
        afterQueue(outgoing.enqueue(listOf(address), body, sub, 0, autoReply = true, batchId = 0, clearDraft = false))
    }

    /**
     * A new message to one or many people. Many recipients become a bulk send: the call returns
     * at once, the queue is filled in the background and a foreground service sends it with a
     * live progress notification. [allowService] is false when Barq is not on screen.
     */
    suspend fun sendNew(
        recipients: List<String>,
        body: String,
        subId: Int?,
        scheduledAt: Long = 0,
        allowService: Boolean = true,
    ): Outcome {
        // Palestinian numbers in any form are sent to their local 05x form; the same person once.
        val unique = PhoneUtils.localPsAll(recipients.map { it.trim() }.filter { it.isNotEmpty() })
        if (unique.isEmpty() || body.isBlank()) return Outcome.Empty
        val sub = resolveSubId(null, subId)
        if (unique.size == 1 || scheduledAt > 0 || !allowService) {
            val queued = outgoing.enqueue(unique, body, sub, scheduledAt, false, 0, clearDraft = unique.size == 1)
            afterQueue(queued)
            return if (queued.threadIds.size == 1) Outcome.Single(queued.threadIds.first())
            else Outcome.Bulk(0, queued.count)
        }
        val batchId = System.currentTimeMillis()
        settings.setLastBatchId(batchId)
        state.preparing.value = true
        // Started now, while Barq is on screen; it waits until the queue is ready.
        val started = BulkSendService.start(context)
        scope.launch {
            try {
                outgoing.enqueue(unique, body, sub, 0, false, batchId, clearDraft = false)
            } finally {
                state.preparing.value = false
            }
            if (!started) queue.kick()
        }
        return Outcome.Bulk(batchId, unique.size)
    }

    private fun afterQueue(queued: OutgoingRepository.Queued) {
        if (queued.count == 0) return
        if (queued.scheduled.isNotEmpty()) queued.scheduled.forEach { scheduler.schedule(it.id, it.scheduledAt) }
        else queue.kick()
    }

    suspend fun resend(messageId: Long) {
        outgoing.resetForResend(listOf(messageId))
        queue.kick()
    }

    /** "Resend failed" after a bulk send (from the progress card or the notification). */
    suspend fun resendFailed(batchId: Long) {
        val failed = messages.failedInBatch(batchId).map { it.id }
        if (failed.isEmpty()) return
        settings.setLastBatchId(batchId)
        outgoing.resetForResend(failed)
        if (failed.size > 1 && BulkSendService.start(context)) return
        queue.kick()
    }

    suspend fun cancelScheduled(messageId: Long) = outgoing.cancelScheduled(messageId)

    fun pauseBatch(batchId: Long) = state.pause(batchId)

    fun resumeBatch() {
        state.resume()
        if (!BulkSendService.start(context)) queue.kick()
    }

    suspend fun cancelBatch(batchId: Long) {
        if (state.paused.value == batchId) state.resume()
        outgoing.cancelBatch(batchId)
    }

    fun observeBatch(batchId: Long): Flow<BatchCounts> = messages.observeBatch(batchId)

    fun observeBatchRows(batchId: Long): Flow<List<BatchRow>> = messages.observeBatchRows(batchId)

    /** What other apps sent since [since] (a gateway, a bot…), followed live. */
    fun observeExternal(since: Long): Flow<BatchCounts> = messages.observeExternal(since)

    fun observeExternalRows(since: Long): Flow<List<BatchRow>> = messages.observeExternalRows(since)

    /** Re-sends, from Barq, what another app failed to send since [since]. */
    suspend fun resendFailedExternal(since: Long) {
        val failed = messages.failedExternal(since).map { it.id }
        if (failed.isEmpty()) return
        outgoing.resetForResend(failed)
        queue.kick()
    }

    suspend fun dismissBatch() = settings.setLastBatchId(0)

    fun kick() = queue.kick()
}
