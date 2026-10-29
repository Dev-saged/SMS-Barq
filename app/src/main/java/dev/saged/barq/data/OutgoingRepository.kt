package dev.saged.barq.data

import android.provider.Telephony
import androidx.room.withTransaction
import dev.saged.barq.data.db.BarqDatabase
import dev.saged.barq.data.db.Box
import dev.saged.barq.data.db.DeliveryStatus
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.db.SendError
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.telephony.TelephonyProvider
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.sms.ScheduleManager
import dev.saged.barq.sms.SendTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The outgoing side of the database: queued rows, their states (outbox → sent / failed /
 * delivered), resending and cancelling. Sending itself is done by [dev.saged.barq.sms.SendDrainer];
 * every screen talks to [dev.saged.barq.sms.SendEngine] instead of this class.
 */
@Singleton
class OutgoingRepository @Inject constructor(
    private val db: BarqDatabase,
    private val provider: TelephonyProvider,
    private val settings: SettingsRepository,
    private val conversations: ConversationRepository,
    private val scheduler: ScheduleManager,
    private val tracker: SendTracker,
    private val lock: ProviderLock,
) {
    private val messages = db.messages()

    data class Queued(val threadIds: List<Long>, val scheduled: List<MessageEntity>, val count: Int)

    /**
     * Queues one plain SMS per recipient (never converted to MMS). Long texts are split into
     * parts by the radio but stay a normal SMS. All rows are written in one transaction, so even
     * a thousand recipients are queued quickly.
     */
    suspend fun enqueue(
        recipients: List<String>,
        body: String,
        subId: Int,
        scheduledAt: Long,
        autoReply: Boolean,
        batchId: Long,
        clearDraft: Boolean,
    ): Queued = withContext(Dispatchers.IO) {
        val text = body.trim()
        val unique = recipients.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { PhoneUtils.matchKey(it) }
        if (text.isEmpty() || unique.isEmpty()) return@withContext Queued(emptyList(), emptyList(), 0)
        val now = System.currentTimeMillis()
        val targets = lock.withLock {
            val resolver = conversations.threadResolver()
            unique.map { it to resolver.resolve(it) }
        }
        val scheduled = ArrayList<MessageEntity>()
        db.withTransaction {
            targets.forEachIndexed { i, (address, threadId) ->
                val msg = MessageEntity(
                    threadId = threadId, address = address, body = text,
                    date = (if (scheduledAt > 0) scheduledAt else now) + i, box = Box.QUEUED,
                    subId = subId, scheduledAt = scheduledAt, autoReply = autoReply, batchId = batchId,
                )
                val id = messages.insert(msg)
                if (scheduledAt > 0) scheduled += msg.copy(id = id)
            }
        }
        val threadIds = targets.map { it.second }.distinct()
        if (clearDraft) threadIds.forEach { conversations.clearDraft(it) }
        if (subId >= 0 && threadIds.size == 1) conversations.setSubId(threadIds.first(), subId)
        conversations.refreshMany(threadIds, revive = true)
        Queued(threadIds, scheduled, targets.size)
    }

    /** Called right before the message is handed to the radio. */
    suspend fun markOutbox(message: MessageEntity): MessageEntity = withContext(Dispatchers.IO) {
        lock.withLock {
            val now = System.currentTimeMillis()
            val providerId = message.providerId ?: provider.insertSms(
                address = message.address, body = message.body, date = now, dateSent = now,
                type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, read = true, threadId = message.threadId,
                subId = message.subId,
            )
            val updated = message.copy(
                box = Box.OUTBOX, date = now, providerId = providerId, errorCode = 0,
                deliveryStatus = if (settings.current().deliveryReports) DeliveryStatus.PENDING else DeliveryStatus.NONE,
            )
            messages.update(updated)
            updated
        }.also { conversations.refresh(it.threadId) }
    }

    suspend fun onSendResult(messageId: Long, success: Boolean, errorCode: Int) = withContext(Dispatchers.IO) {
        tracker.complete(messageId, success)
        val msg = messages.get(messageId) ?: return@withContext
        if (msg.box == Box.FAILED && success) return@withContext // an earlier part already failed
        val box = if (success) Box.SENT else Box.FAILED
        messages.setBox(messageId, box, if (success) 0 else errorCode)
        msg.providerId?.let {
            provider.updateType(
                it, if (success) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED,
                if (errorCode > 0) errorCode else 0,
            )
        }
        conversations.refresh(msg.threadId)
    }

    suspend fun onDelivery(messageId: Long, delivered: Boolean) = withContext(Dispatchers.IO) {
        val msg = messages.get(messageId) ?: return@withContext
        val status = if (delivered) DeliveryStatus.DELIVERED else DeliveryStatus.FAILED
        messages.setDelivery(messageId, status)
        msg.providerId?.let {
            provider.updateStatus(it, if (delivered) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_FAILED)
        }
    }

    /** Puts failed messages back in the queue (keeps their bulk-send batch). */
    suspend fun resetForResend(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val threads = HashSet<Long>()
        db.withTransaction {
            ids.forEachIndexed { i, id ->
                val msg = messages.get(id) ?: return@forEachIndexed
                if (msg.box != Box.FAILED && msg.box != Box.QUEUED) return@forEachIndexed
                messages.update(msg.copy(box = Box.QUEUED, scheduledAt = 0, errorCode = 0, date = now + i))
                threads += msg.threadId
            }
        }
        conversations.refreshMany(threads)
    }

    suspend fun cancelScheduled(messageId: Long) = withContext(Dispatchers.IO) {
        val msg = messages.get(messageId) ?: return@withContext
        scheduler.cancel(messageId)
        messages.deleteByIds(listOf(messageId))
        conversations.refresh(msg.threadId)
    }

    /** Stops a bulk send: what has not gone out yet is marked "cancelled" and can be resent. */
    suspend fun cancelBatch(batchId: Long) = withContext(Dispatchers.IO) {
        if (batchId == 0L) return@withContext
        messages.cancelBatch(batchId, SendError.CANCELLED)
        conversations.refreshMany(messages.threadsInBatch(batchId))
    }
}
