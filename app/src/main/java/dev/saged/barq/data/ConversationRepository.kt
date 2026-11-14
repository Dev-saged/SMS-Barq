package dev.saged.barq.data

import dev.saged.barq.domain.Classifier
import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.R
import dev.saged.barq.data.contacts.ContactsRepository
import dev.saged.barq.data.db.BarqDatabase
import dev.saged.barq.data.db.Box
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.CategoryRuleEntity
import dev.saged.barq.data.db.ConversationEntity
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.db.TrashedMessageEntity
import dev.saged.barq.data.db.isIncoming
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.telephony.TelephonyProvider
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.notifications.Notifier
import dev.saged.barq.sms.ScheduleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything about conversations: their summary row (snippet, time, unread, category), user flags
 * (pin, mute, archive), drafts, the recycle bin, and the rule "one conversation per real number".
 */
@Singleton
class ConversationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: BarqDatabase,
    private val provider: TelephonyProvider,
    private val contacts: ContactsRepository,
    private val settings: SettingsRepository,
    private val blockList: BlockList,
    private val scheduler: ScheduleManager,
    private val notifier: Notifier,
    private val lock: ProviderLock,
) {
    private val conversations = db.conversations()
    private val messages = db.messages()
    private val rules = db.rules()
    private val trash = db.trash()

    // ---------------------------------------------------------------- summary row

    /**
     * Recomputes snippet, time, unread count and category from the stored messages while keeping
     * user flags. [revive] brings archived (incoming only) and deleted conversations back.
     * Messages scheduled for later never change the conversation's time or position.
     */
    suspend fun refresh(threadId: Long, revive: Boolean = false): ConversationEntity? =
        withContext(Dispatchers.IO) { refreshNow(threadId, revive) }

    /** Refreshes many conversations in one transaction (bulk send, import, sync). */
    suspend fun refreshMany(threadIds: Collection<Long>, revive: Boolean = false) {
        if (threadIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            threadIds.chunked(200).forEach { chunk ->
                db.withTransaction { chunk.forEach { refreshNow(it, revive) } }
            }
        }
    }

    /** Must not switch dispatchers: it also runs inside Room transactions. */
    private suspend fun refreshNow(threadId: Long, revive: Boolean): ConversationEntity? {
        val visible = messages.latestVisible(threadId)
        val latest = visible ?: messages.latest(threadId)
        val existing = conversations.get(threadId)
        if (latest == null) {
            // A conversation opened from another app (date = 0) stays while it is on screen.
            if (existing != null && existing.draft.isNullOrBlank() && existing.date > 0) {
                conversations.deleteForever(listOf(threadId))
                return null
            }
            return existing
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
            latest.isMms -> context.getString(R.string.media_placeholder)
            else -> ""
        }
        // Only scheduled messages so far: keep the conversation where it is.
        val date = if (visible == null) {
            existing?.date?.takeIf { it > 0 } ?: System.currentTimeMillis()
        } else latest.date
        val base = existing ?: ConversationEntity(
            threadId = threadId, addresses = addresses, displayName = addresses,
            snippet = snippet, date = date, subId = latest.subId,
        )
        val conv = base.copy(
            displayName = contacts.displayName(addresses),
            snippet = snippet.take(300),
            date = date,
            unreadCount = messages.unreadCount(threadId),
            category = category,
            archived = if (revive && latest.isIncoming) false else base.archived,
            deleted = if (revive) false else base.deleted,
            lastFromMe = !latest.isIncoming,
            lastBox = latest.box,
        )
        conversations.upsert(conv)
        return conv
    }

    // ---------------------------------------------------------------- flags

    suspend fun get(threadId: Long): ConversationEntity? = conversations.get(threadId)

    suspend fun markRead(threadIds: List<Long>) = withContext(Dispatchers.IO) {
        if (threadIds.isEmpty()) return@withContext
        provider.markRead(messages.unreadProviderIds(threadIds))
        messages.markRead(threadIds)
        conversations.clearUnread(threadIds)
    }

    /**
     * "Mark as unread": the newest received message of each conversation becomes unread again
     * (a conversation with nothing received stays as it is). No notification is shown for it.
     */
    suspend fun markUnread(threadIds: List<Long>) = withContext(Dispatchers.IO) {
        threadIds.forEach { threadId ->
            val latest = messages.latestIncoming(threadId) ?: return@forEach
            latest.providerId?.let { provider.markUnread(it) }
            messages.markUnread(latest.id)
            conversations.setUnreadCount(threadId, messages.unreadCount(threadId))
        }
    }

    /** Everything unread in the inbox (or in one category) becomes read. Returns how many conversations. */
    suspend fun markAllRead(category: Category?): Int = withContext(Dispatchers.IO) {
        val ids = conversations.unreadIds(category?.name)
        ids.chunked(500).forEach { markRead(it) }
        ids.forEach { notifier.cancel(it) }
        ids.size
    }

    suspend fun setPinned(ids: List<Long>, pinned: Boolean) = conversations.setPinned(ids, pinned)
    suspend fun setArchived(ids: List<Long>, archived: Boolean) = conversations.setArchived(ids, archived)
    suspend fun setMuted(ids: List<Long>, muted: Boolean) = conversations.setMuted(ids, muted)
    suspend fun setSubId(threadId: Long, subId: Int) = conversations.setSubId(threadId, subId)

    /**
     * Saves a draft. The draft time changes only when the text really changed, so opening a
     * conversation and leaving it does not move it in the list.
     */
    suspend fun setDraft(threadId: Long, draft: String?) {
        val text = draft?.takeIf { it.isNotBlank() }
        val conv = conversations.get(threadId) ?: return
        if (conv.draft == text) return
        conversations.setDraft(threadId, text, if (text != null) System.currentTimeMillis() else 0L)
    }

    suspend fun clearDraft(threadId: Long) = conversations.setDraft(threadId, null, 0L)

    // ---------------------------------------------------------------- delete / bin

    /** Moves to the recycle bin, or deletes immediately when the bin is turned off. */
    suspend fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        if (settings.current().recycleBin) conversations.setDeleted(ids, true, System.currentTimeMillis())
        else deleteForever(ids)
        ids.forEach { notifier.cancel(it) }
    }

    suspend fun restore(ids: List<Long>) = conversations.setDeleted(ids, false, 0)

    suspend fun deleteForever(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        lock.withLock {
            provider.deleteSms(messages.providerIds(ids))
            messages.mmsIn(ids).flatMap { it.attachments }.forEach { runCatching { File(it.path).delete() } }
            ids.forEach { scheduler.cancelForThread(it) }
            db.withTransaction {
                messages.deleteThreads(ids)
                conversations.deleteForever(ids)
            }
        }
        ids.forEach {
            notifier.cancel(it)
            notifier.deleteConversationChannel(it)
        }
    }

    suspend fun emptyRecycleBin() {
        deleteForever(conversations.allDeletedIds())
        deleteTrashedForever(trash.allIds())
    }

    /** Items older than 30 days leave the recycle bin automatically. */
    suspend fun purgeRecycleBin() {
        val before = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        deleteForever(conversations.deletedBefore(before))
        deleteTrashedForever(trash.idsBefore(before))
    }

    suspend fun deleteMessage(messageId: Long) = deleteMessages(listOf(messageId))

    /** Only messages that are final (received, sent, failed) can wait in the bin; queued ones are simply cancelled. */
    private fun binnable(m: MessageEntity) =
        m.reactionTo == 0L && (m.box == Box.INBOX || m.box == Box.SENT || m.box == Box.FAILED)

    /** يحذف الرسائل المحددة فقط وتبقى المحادثات؛ تنتقل إلى السلة إن كانت مفعّلة. */
    suspend fun deleteMessages(ids: List<Long>) = withContext(Dispatchers.IO) {
        val found = ids.mapNotNull { messages.get(it) }
        if (found.isEmpty()) return@withContext
        val kept = if (settings.current().recycleBin) found.filter(::binnable) else emptyList()
        val keptIds = kept.mapTo(HashSet()) { it.id }
        lock.withLock {
            provider.deleteSms(found.mapNotNull { it.providerId })
            found.forEach { msg ->
                if (msg.id !in keptIds) msg.attachments.forEach { runCatching { File(it.path).delete() } }
                scheduler.cancel(msg.id)
            }
            val now = System.currentTimeMillis()
            db.withTransaction {
                if (kept.isNotEmpty()) trash.insertAll(kept.map { it.toTrash(now) })
                messages.deleteByIds(found.map { it.id })
            }
        }
        refreshMany(found.map { it.threadId }.toSet())
    }

    /** Puts binned messages back into their conversation (and into the system's SMS store). */
    suspend fun restoreMessages(ids: List<Long>) = withContext(Dispatchers.IO) {
        val rows = trash.get(ids)
        if (rows.isEmpty()) return@withContext
        val touched = HashSet<Long>()
        lock.withLock {
            rows.forEach { t ->
                // The conversation may be gone (it was its last message): ask the system for the number's thread.
                val threadId = if (t.isMms || conversations.get(t.threadId) != null) t.threadId
                else provider.threadIdFor(listOf(t.address))
                val providerId = if (t.isMms) null else provider.insertSms(
                    address = t.address, body = t.body, date = t.date, dateSent = t.dateSent,
                    type = t.box, read = true, threadId = threadId, subId = t.subId,
                )
                db.withTransaction {
                    messages.insert(
                        MessageEntity(
                            providerId = providerId, threadId = threadId, address = t.address, body = t.body,
                            date = t.date, dateSent = t.dateSent, box = t.box, read = true, subId = t.subId,
                            otp = t.otp, category = t.category, isMms = t.isMms, attachments = t.attachments,
                            subject = t.subject, starred = t.starred,
                        )
                    )
                    trash.delete(listOf(t.id))
                }
                touched += threadId
            }
        }
        refreshMany(touched, revive = true)
    }

    suspend fun deleteTrashedForever(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        ids.chunked(500).forEach { chunk ->
            trash.get(chunk).flatMap { it.attachments }.forEach { runCatching { File(it.path).delete() } }
            trash.delete(chunk)
        }
    }

    private fun MessageEntity.toTrash(now: Long) = TrashedMessageEntity(
        threadId = threadId, address = address, body = body, date = date, dateSent = dateSent, box = box,
        subId = subId, category = category, isMms = isMms, attachments = attachments, subject = subject,
        otp = otp, starred = starred, deletedAt = now,
    )

    // ---------------------------------------------------------------- rules / block

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

    // ---------------------------------------------------------------- names

    /**
     * Updates conversation names from the contacts index. [rebuildIndex] re-reads the phone's
     * contacts first (after the permission is granted or contacts changed). Only conversations
     * whose name really changed are written.
     */
    suspend fun refreshNames(rebuildIndex: Boolean) = withContext(Dispatchers.IO) {
        if (rebuildIndex) contacts.rebuild() else contacts.warmUp()
        conversations.all().forEach { conv ->
            val name = contacts.displayName(conv.addresses)
            if (name != conv.displayName) conversations.setDisplayName(conv.threadId, name)
        }
        reclassify()
    }

    /**
     * Puts conversations back where they belong with today's rules: a saved contact is personal
     * (never codes, banks or offers), and a person's number is not a bank. Categories the user
     * chose by hand are never touched. Runs after contacts change and once after updating.
     */
    suspend fun reclassify() = withContext(Dispatchers.IO) {
        val s = settings.current()
        val ruleList = rules.all()
        conversations.all().forEach { conv ->
            if (conv.categoryLocked || conv.category == Category.PERSONAL) return@forEach
            val address = PhoneUtils.splitAddresses(conv.addresses).singleOrNull() ?: return@forEach
            val latest = messages.latestIncoming(conv.threadId) ?: return@forEach
            val category = Classifier.classify(address, latest.body, latest.otp, contacts.isContact(address), s.spamFilter, ruleList)
            if (category != conv.category) {
                conversations.setCategory(conv.threadId, category, locked = false)
                messages.setIncomingCategory(conv.threadId, category)
            }
        }
    }

    // ---------------------------------------------------------------- one conversation per number

    /**
     * Maps a number to the conversation that already holds it in any format, so
     * 0597857250, +972597857250 and +970597857250 always land in the same thread.
     * Built once per operation (cheap even for thousands of conversations).
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
     * The conversation for [address] — the existing one in any number format, or a new empty one.
     * Used when another app (contacts, call log, a sms: link) asks to message someone.
     * [draft] (text sent by that app) goes into the message box, it is never sent automatically.
     */
    suspend fun openOrCreate(address: String, draft: String?): Long = withContext(Dispatchers.IO) {
        val threadId = lock.withLock { threadResolver().resolve(address) }
        val text = draft?.takeIf { it.isNotBlank() }
        val existing = conversations.get(threadId)
        if (existing == null) {
            conversations.upsert(
                ConversationEntity(
                    threadId = threadId,
                    addresses = address,
                    displayName = contacts.displayName(address),
                    snippet = "",
                    date = if (messages.latest(threadId) != null) System.currentTimeMillis() else 0L,
                    draft = text,
                    draftAt = if (text != null) System.currentTimeMillis() else 0L,
                    lastFromMe = true,
                    lastBox = Box.SENT,
                )
            )
            if (messages.latest(threadId) != null) refreshNow(threadId, revive = true)
        } else {
            if (existing.deleted) conversations.setDeleted(listOf(threadId), false, 0)
            if (text != null) setDraft(threadId, text)
        }
        threadId
    }

    /** Removes a conversation opened from another app if it was left without a message or draft. */
    suspend fun dropIfEmpty(threadId: Long) {
        val conv = conversations.get(threadId) ?: return
        if (conv.date == 0L && conv.draft == null && messages.latest(threadId) == null) {
            conversations.deleteForever(listOf(threadId))
        }
    }

    suspend fun purgeEmpty() {
        val ids = conversations.emptyIds()
        if (ids.isNotEmpty()) conversations.deleteForever(ids)
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
        lock.withLock {
            db.withTransaction {
                groups.forEach { list ->
                    val target = list.maxByOrNull { it.date } ?: return@forEach
                    list.filter { it.threadId != target.threadId }.forEach { other ->
                        messages.moveThread(other.threadId, target.threadId)
                        conversations.deleteForever(listOf(other.threadId))
                    }
                    val draftOwner = list.filter { it.draft != null }.maxByOrNull { it.draftAt }
                    conversations.upsert(
                        target.copy(
                            pinned = list.any { it.pinned },
                            muted = list.any { it.muted },
                            archived = list.all { it.archived },
                            deleted = list.all { it.deleted },
                            draft = target.draft ?: draftOwner?.draft,
                            draftAt = if (target.draft != null) target.draftAt else draftOwner?.draftAt ?: 0L,
                            categoryLocked = list.any { it.categoryLocked },
                        )
                    )
                    targets += target.threadId
                }
            }
        }
        refreshMany(targets)
    }
}
