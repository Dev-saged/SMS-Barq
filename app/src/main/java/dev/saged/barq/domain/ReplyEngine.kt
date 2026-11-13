package dev.saged.barq.domain

import androidx.room.withTransaction
import dev.saged.barq.data.db.BarqDatabase
import dev.saged.barq.data.db.Box
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.ConversationDao
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.db.ReplyDao
import dev.saged.barq.data.db.ReplyMemoryEntity
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.di.AppScope
import dev.saged.barq.util.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The local reply engine: learns from the replies the user writes and suggests them again.
 * Everything stays in the phone's database; there is no model and no network.
 *  - learning: each reply the user sends is paired with the text it answered (one conversation
 *    with one person, personal senders only), and the whole history can be re-read on demand;
 *  - suggesting: [suggest] feeds the chips above the composer, [best] feeds the auto-reply.
 */
@Singleton
class ReplyEngine @Inject constructor(
    private val db: BarqDatabase,
    private val replies: ReplyDao,
    private val messages: MessageDao,
    private val conversations: ConversationDao,
    private val settings: SettingsRepository,
    @AppScope private val scope: CoroutineScope,
) {
    val count: Flow<Int> = replies.observeCount()
    val busy = MutableStateFlow(false)

    @Volatile private var cached: ReplyIndex? = null
    private val lock = Mutex()

    private suspend fun index(): ReplyIndex = cached ?: lock.withLock {
        cached ?: ReplyIndex(replies.all().map { Memory(it.incoming, it.reply, it.uses, it.lastUsed) }).also { cached = it }
    }

    /** Chips for [incoming]; empty when learning is switched off. */
    suspend fun suggest(incoming: String, limit: Int = 3): List<Suggestion> {
        if (!settings.current().learnReplies) return emptyList()
        return index().suggest(incoming, limit, System.currentTimeMillis())
    }

    /** The reply sent automatically, only when confident enough. */
    suspend fun best(incoming: String): String? {
        if (!settings.current().learnReplies) return null
        return index().best(incoming, System.currentTimeMillis())?.reply
    }

    /** For the "try it" dialog: what the engine would do with [incoming], learning switch aside. */
    suspend fun preview(incoming: String): List<Suggestion> =
        index().suggest(incoming, 3, System.currentTimeMillis(), withPopular = false)

    /** Called after the user sent [body] in [threadId]: learns it when it answers an incoming text. */
    suspend fun onUserSent(threadId: Long, body: String) {
        if (!settings.current().learnReplies) return
        val conversation = conversations.get(threadId) ?: return
        if (PhoneUtils.splitAddresses(conversation.addresses).size != 1) return
        val (mine, previous) = messages.lastTurns(threadId).let { it.getOrNull(0) to it.getOrNull(1) }
        if (mine == null || previous == null || previous.box != Box.INBOX || mine.box == Box.INBOX) return
        if (mine.body.trim() != body.trim()) return
        val walker = ReplyPairing.Walker(ReplyText.nameWords(conversation.displayName))
        walker.feed(
            Turn(
                incoming = true, body = previous.body, date = previous.date,
                eligible = previous.category == Category.PERSONAL && PhoneUtils.isReplyable(previous.address),
            ),
        )
        val pair = walker.feed(Turn(incoming = false, body = mine.body, date = mine.date)) ?: return
        val key = ReplyText.key(pair.incoming)
        if (replies.bump(key, pair.reply, pair.at) == 0) {
            replies.insert(ReplyMemoryEntity(incoming = key, reply = pair.reply, uses = 1, lastUsed = pair.at))
            val over = replies.count() - MAX_ROWS
            if (over > 0) replies.prune(over)
        }
        cached = null
    }

    /** Re-reads the whole history in the background (the memory is rebuilt from scratch). */
    fun rebuild() {
        scope.launch { runCatching { rebuildNow() }.onFailure { CrashLog.warn("reply-learn", it) } }
    }

    fun rebuildIfEmpty() {
        scope.launch {
            if (settings.current().learnReplies && replies.count() == 0) {
                runCatching { rebuildNow() }.onFailure { CrashLog.warn("reply-learn", it) }
            }
        }
    }

    suspend fun clear() {
        replies.clear()
        cached = null
    }

    private suspend fun rebuildNow() {
        if (!busy.compareAndSet(false, true)) return
        try {
            val solo = conversations.names()
                .filter { PhoneUtils.splitAddresses(it.addresses).size == 1 }
                .associate { it.threadId to ReplyText.nameWords(it.displayName) }
            val found = HashMap<kotlin.Pair<String, String>, Memory>()
            var thread = -1L
            var walker: ReplyPairing.Walker? = null
            var t = Long.MIN_VALUE
            var d = Long.MIN_VALUE
            var i = Long.MIN_VALUE
            while (true) {
                val page = messages.learnRows(t, d, i, PAGE)
                if (page.isEmpty()) break
                for (row in page) {
                    if (row.threadId != thread) {
                        thread = row.threadId
                        walker = solo[thread]?.let { ReplyPairing.Walker(it) }
                    }
                    val w = walker ?: continue
                    val incoming = row.box == Box.INBOX
                    val pair = w.feed(
                        Turn(
                            incoming = incoming, body = row.body, date = row.date, auto = row.autoReply,
                            eligible = row.category == Category.PERSONAL && PhoneUtils.isReplyable(row.address),
                        ),
                    ) ?: continue
                    val key = ReplyText.key(pair.incoming)
                    val k = key to pair.reply
                    val old = found[k]
                    found[k] = Memory(key, pair.reply, (old?.uses ?: 0) + 1, maxOf(old?.lastUsed ?: 0L, pair.at))
                }
                val last = page.last()
                t = last.threadId; d = last.date; i = last.id
            }
            val kept = found.values
                .sortedWith(compareByDescending<Memory> { it.uses }.thenByDescending { it.lastUsed })
                .take(MAX_ROWS)
                .map { ReplyMemoryEntity(incoming = it.incoming, reply = it.reply, uses = it.uses, lastUsed = it.lastUsed) }
            db.withTransaction {
                replies.clear()
                replies.insertAll(kept)
            }
            cached = null
        } finally {
            busy.value = false
        }
    }

    private companion object {
        const val MAX_ROWS = 4000
        const val PAGE = 2000
    }
}
