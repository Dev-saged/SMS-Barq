package dev.saged.barq.data

import android.content.Context
import android.provider.Telephony
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.data.contacts.ContactsRepository
import dev.saged.barq.data.db.Attachment
import dev.saged.barq.data.db.BarqDatabase
import dev.saged.barq.data.db.Box
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.CategoryRuleEntity
import dev.saged.barq.data.db.ConversationEntity
import dev.saged.barq.data.db.DeliveryStatus
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.db.isIncoming
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.telephony.TelephonyProvider
import dev.saged.barq.domain.Classifier
import dev.saged.barq.domain.OtpDetector
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.sms.ScheduleManager
import dev.saged.barq.sms.SendQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for messages. Room is what the UI reads (fast, paged); the system SMS
 * provider is kept in sync so other apps and future migrations still see every message.
 */
@Singleton
class MessageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: BarqDatabase,
    private val provider: TelephonyProvider,
    private val contacts: ContactsRepository,
    private val settings: SettingsRepository,
    private val blockList: BlockList,
    private val sendQueue: SendQueue,
    private val scheduler: ScheduleManager,
) {
    private val conversations = db.conversations()
    private val messages = db.messages()
    private val rules = db.rules()

    /** Serialises provider writes and provider sync so rows are never imported twice. */
    val providerLock = Mutex()

    // ---------------------------------------------------------------- incoming

    data class Incoming(val message: MessageEntity, val conversation: ConversationEntity)

    /** Stores an incoming SMS. Returns null when the sender or content is blocked. */
    suspend fun onIncomingSms(address: String, body: String, dateSent: Long, subId: Int): Incoming? =
        withContext(Dispatchers.IO) {
            if (blockList.isBlockedNumber(address) || blockList.hasBlockedKeyword(body)) return@withContext null
            val now = System.currentTimeMillis()
            val s = settings.current()
            val otp = OtpDetector.detect(body)
            val category = Classifier.classify(
                address, body, otp, contacts.isContact(address), s.spamFilter, rules.all()
            )
            val stored = providerLock.withLock {
                val threadId = threadResolver().resolve(address)
                val providerId = provider.insertSms(
                    address = address, body = body, date = now, dateSent = dateSent,
                    type = Telephony.Sms.MESSAGE_TYPE_INBOX, read = false, threadId = threadId, subId = subId,
                )
                val msg = MessageEntity(
                    providerId = providerId, threadId = threadId, address = address, body = body,
                    date = now, dateSent = dateSent, box = Box.INBOX, read = false, subId = subId,
                    otp = otp, category = category,
                )
                msg.copy(id = messages.insert(msg))
            }
            val conv = refreshConversation(stored.threadId, revive = true) ?: return@withContext null
            Incoming(stored, conv)
        }

    suspend fun onIncomingMms(
        address: String,
        text: String,
        subject: String?,
        attachments: List<Attachment>,
        date: Long,
        subId: Int,
    ): Incoming? = withContext(Dispatchers.IO) {
        if (blockList.isBlockedNumber(address) || blockList.hasBlockedKeyword(text)) return@withContext null
        val s = settings.current()
        val otp = OtpDetector.detect(text)
        val category = Classifier.classify(address, text, otp, contacts.isContact(address), s.spamFilter, rules.all())
        val threadId = threadResolver().resolve(address)
        val msg = MessageEntity(
            threadId = threadId, address = address, body = text, date = date, dateSent = date,
            box = Box.INBOX, read = false, subId = subId, otp = otp, category = category,
            isMms = true, attachments = attachments, subject = subject,
        )
        val stored = msg.copy(id = messages.insert(msg))
        val conv = refreshConversation(threadId, revive = true) ?: return@withContext null
        Incoming(stored, conv)
    }

    // ---------------------------------------------------------------- outgoing

    /**
     * Queues one plain SMS per recipient (never converted to MMS). Long texts are split into
     * parts by the radio but stay a normal SMS. Returns the thread ids used.
     */
    suspend fun queueOutgoing(
        recipients: List<String>,
        body: String,
        subId: Int,
        scheduledAt: Long = 0,
        autoReply: Boolean = false,
    ): List<Long> = withContext(Dispatchers.IO) {
        val text = body.trim()
        if (text.isEmpty() || recipients.isEmpty()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        val threadIds = ArrayList<Long>(recipients.size)
        val scheduled = ArrayList<MessageEntity>()
        // One transaction for the whole batch keeps 500 recipients fast.
        val touched = providerLock.withLock {
            val resolver = threadResolver()
            db.withTransaction {
                recipients.distinctBy { PhoneUtils.matchKey(it) }.forEachIndexed { i, address ->
                    val threadId = resolver.resolve(address)
                    val msg = MessageEntity(
                        threadId = threadId, address = address, body = text,
                        date = (if (scheduledAt > 0) scheduledAt else now) + i, box = Box.QUEUED,
                        subId = subId, scheduledAt = scheduledAt, autoReply = autoReply,
                    )
                    val id = messages.insert(msg)
                    if (scheduledAt > 0) scheduled += msg.copy(id = id)
                    threadIds += threadId
                }
            }
            threadIds.toSet()
        }
        touched.forEach { tid ->
            if (!autoReply) conversations.setDraft(tid, null)
            if (subId >= 0) conversations.setSubId(tid, subId)
            refreshConversation(tid, revive = true)
        }
        if (scheduled.isNotEmpty()) scheduled.forEach { scheduler.schedule(it.id, it.scheduledAt) }
        else sendQueue.kick()
        threadIds
    }

    suspend fun queueToThread(threadId: Long, body: String, subId: Int, scheduledAt: Long = 0): Boolean {
        val conv = conversations.get(threadId) ?: return false
        val recipients = PhoneUtils.splitAddresses(conv.addresses)
        return queueOutgoing(recipients, body, subId, scheduledAt).isNotEmpty()
    }

    /** Called by the send queue right before the message is handed to the radio. */
    suspend fun markOutbox(message: MessageEntity): MessageEntity = withContext(Dispatchers.IO) {
        providerLock.withLock {
            val now = System.currentTimeMillis()
            val providerId = message.providerId ?: provider.insertSms(
                address = message.address, body = message.body, date = now, dateSent = now,
                type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, read = true, threadId = message.threadId,
                subId = message.subId,
            )
            val updated = message.copy(
                box = Box.OUTBOX, date = now, providerId = providerId,
                deliveryStatus = if (settings.current().deliveryReports) DeliveryStatus.PENDING else DeliveryStatus.NONE,
            )
            messages.update(updated)
            updated
        }.also { refreshConversation(it.threadId) }
    }

    suspend fun onSendResult(messageId: Long, success: Boolean, errorCode: Int) = withContext(Dispatchers.IO) {
        val msg = messages.get(messageId) ?: return@withContext
        if (msg.box == Box.FAILED && success) return@withContext // an earlier part already failed
        val box = if (success) Box.SENT else Box.FAILED
        messages.setBox(messageId, box, if (success) 0 else errorCode)
        msg.providerId?.let {
            provider.updateType(
                it, if (success) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED, errorCode
            )
        }
        refreshConversation(msg.threadId)
    }

    suspend fun onDelivery(messageId: Long, delivered: Boolean) = withContext(Dispatchers.IO) {
        val msg = messages.get(messageId) ?: return@withContext
        val status = if (delivered) DeliveryStatus.DELIVERED else DeliveryStatus.FAILED
        messages.setDelivery(messageId, status)
        msg.providerId?.let {
            provider.updateStatus(it, if (delivered) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_FAILED)
        }
    }

    suspend fun resend(messageId: Long) = withContext(Dispatchers.IO) {
        val msg = messages.get(messageId) ?: return@withContext
        messages.update(msg.copy(box = Box.QUEUED, scheduledAt = 0, errorCode = 0, date = System.currentTimeMillis()))
        refreshConversation(msg.threadId)
        sendQueue.kick()
    }

    suspend fun cancelScheduled(messageId: Long) = withContext(Dispatchers.IO) {
        val msg = messages.get(messageId) ?: return@withContext
        scheduler.cancel(messageId)
        messages.deleteByIds(listOf(messageId))
        refreshConversation(msg.threadId)
    }

    // ---------------------------------------------------------------- conversation state

    /**
     * Recomputes snippet, date, unread count and category from the stored messages while
     * keeping user flags (pinned, muted…). [revive] brings archived/deleted threads back.
     */
    suspend fun refreshConversation(threadId: Long, revive: Boolean = false): ConversationEntity? =
        withContext(Dispatchers.IO) {
            val latest = messages.latest(threadId)
            val existing = conversations.get(threadId)
            if (latest == null) {
                if (existing != null && existing.draft.isNullOrBlank()) conversations.deleteForever(listOf(threadId))
                return@withContext existing
            }
            val addresses = existing?.addresses ?: latest.address
            val incoming = if (latest.isIncoming) latest else messages.latestIncoming(threadId)
            val category = when {
                existing?.categoryLocked == true -> existing.category
                incoming != null -> incoming.category
                else -> existing?.category ?: Category.PERSONAL
            }
            val snippet = when {
                latest.body.isNotBlank() -> latest.body
                latest.isMms -> "[وسائط]"
                else -> ""
            }
            val base = existing ?: ConversationEntity(
                threadId = threadId, addresses = addresses, displayName = addresses,
                snippet = snippet, date = latest.date, subId = latest.subId,
            )
            val conv = base.copy(
                displayName = contacts.displayName(addresses),
                snippet = snippet.take(300),
                date = latest.date,
                unreadCount = messages.unreadCount(threadId),
                category = category,
                archived = if (revive && latest.isIncoming) false else base.archived,
                deleted = if (revive) false else base.deleted,
                lastFromMe = !latest.isIncoming,
                lastBox = latest.box,
            )
            conversations.upsert(conv)
            conv
        }

    suspend fun markRead(threadIds: List<Long>) = withContext(Dispatchers.IO) {
        if (threadIds.isEmpty()) return@withContext
        provider.markRead(messages.unreadProviderIds(threadIds))
        messages.markRead(threadIds)
        conversations.clearUnread(threadIds)
    }

    suspend fun setPinned(ids: List<Long>, pinned: Boolean) = conversations.setPinned(ids, pinned)
    suspend fun setArchived(ids: List<Long>, archived: Boolean) = conversations.setArchived(ids, archived)
    suspend fun setMuted(ids: List<Long>, muted: Boolean) = conversations.setMuted(ids, muted)
    suspend fun setDraft(threadId: Long, draft: String?) =
        conversations.setDraft(threadId, draft?.takeIf { it.isNotBlank() })
    suspend fun setSubId(threadId: Long, subId: Int) = conversations.setSubId(threadId, subId)

    /** Moves to the recycle bin, or deletes immediately when the bin is turned off. */
    suspend fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        if (settings.current().recycleBin) conversations.setDeleted(ids, true, System.currentTimeMillis())
        else deleteForever(ids)
    }

    suspend fun restore(ids: List<Long>) = conversations.setDeleted(ids, false, 0)

    suspend fun deleteForever(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        providerLock.withLock {
            provider.deleteSms(messages.providerIds(ids))
            messages.mmsIn(ids).flatMap { it.attachments }.forEach { runCatching { File(it.path).delete() } }
            ids.forEach { scheduler.cancelForThread(it) }
            db.withTransaction {
                messages.deleteThreads(ids)
                conversations.deleteForever(ids)
            }
        }
    }

    suspend fun emptyRecycleBin() = deleteForever(conversations.allDeletedIds())

    /** Items older than 30 days leave the recycle bin automatically. */
    suspend fun purgeRecycleBin() {
        val before = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        deleteForever(conversations.deletedBefore(before))
    }

    suspend fun deleteMessage(messageId: Long) = withContext(Dispatchers.IO) {
        val msg = messages.get(messageId) ?: return@withContext
        providerLock.withLock {
            msg.providerId?.let { provider.deleteSms(listOf(it)) }
            msg.attachments.forEach { runCatching { File(it.path).delete() } }
            scheduler.cancel(messageId)
            messages.deleteByIds(listOf(messageId))
        }
        refreshConversation(msg.threadId)
    }

    /** "Always put this sender in X": saves a rule and re-labels the conversation. */
    suspend fun setCategoryForSender(threadId: Long, category: Category) {
        val conv = conversations.get(threadId) ?: return
        PhoneUtils.splitAddresses(conv.addresses).forEach { address ->
            val existing = rules.all().firstOrNull { PhoneUtils.same(it.sender, address) }
            rules.upsert(CategoryRuleEntity(id = existing?.id ?: 0, sender = address, category = category))
        }
        conversations.setCategory(threadId, category, locked = true)
    }

    suspend fun block(threadIds: List<Long>) {
        threadIds.forEach { id ->
            conversations.get(id)?.let { conv ->
                PhoneUtils.splitAddresses(conv.addresses).forEach { blockList.blockNumber(it) }
            }
        }
        delete(threadIds)
    }

    /** Refreshes contact names (after the permission is granted or contacts change). */
    suspend fun refreshNames() = withContext(Dispatchers.IO) {
        contacts.invalidate()
        contacts.warmUp()
        conversations.all().forEach { conv ->
            val name = contacts.displayName(conv.addresses)
            if (name != conv.displayName) conversations.setDisplayName(conv.threadId, name)
        }
    }

    // ---------------------------------------------------------------- one conversation per number

    /**
     * Maps a number to the conversation that already holds it in any format, so
     * 0597857250, +972597857250 and +970597857250 always land in the same thread.
     * Built once per operation (cheap even for hundreds of conversations).
     */
    inner class ThreadResolver(private val byKey: MutableMap<String, Long>) {
        fun resolve(address: String): Long {
            val key = PhoneUtils.conversationKey(address)
            return byKey.getOrPut(key) { provider.threadIdFor(listOf(address)) }
        }
    }

    suspend fun threadResolver(): ThreadResolver {
        val map = HashMap<String, Long>()
        conversations.all().sortedByDescending { it.date }.forEach { conv ->
            map.putIfAbsent(PhoneUtils.conversationKey(conv.addresses), conv.threadId)
        }
        return ThreadResolver(map)
    }

    /**
     * Joins conversations that are really the same number written differently (older data,
     * or messages imported from the phone). Keeps the most recent thread and its flags.
     */
    suspend fun mergeDuplicateConversations() = withContext(Dispatchers.IO) {
        val groups = conversations.all()
            .groupBy { PhoneUtils.conversationKey(it.addresses) }
            .values.filter { it.size > 1 }
        if (groups.isEmpty()) return@withContext
        val targets = ArrayList<Long>()
        providerLock.withLock {
            db.withTransaction {
                groups.forEach { list ->
                    val target = list.maxByOrNull { it.date } ?: return@forEach
                    list.filter { it.threadId != target.threadId }.forEach { other ->
                        messages.moveThread(other.threadId, target.threadId)
                        conversations.deleteForever(listOf(other.threadId))
                    }
                    conversations.upsert(
                        target.copy(
                            pinned = list.any { it.pinned },
                            muted = list.any { it.muted },
                            archived = list.all { it.archived },
                            deleted = list.all { it.deleted },
                            draft = target.draft ?: list.firstNotNullOfOrNull { it.draft },
                            categoryLocked = list.any { it.categoryLocked },
                        )
                    )
                    targets += target.threadId
                }
            }
        }
        targets.forEach { refreshConversation(it) }
    }

    fun attachmentDir(): File = File(context.filesDir, "attachments").apply { mkdirs() }
}
