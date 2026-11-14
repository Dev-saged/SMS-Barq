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
import java.util.Calendar
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
        val s = settings.current()
        if (!s.learnReplies) return emptyList()
        val index = index()
        val own = index.suggest(incoming, limit, System.currentTimeMillis())
        val composed = ReplyIntents.reply(incoming, index.addressTerm, withBusy = false, tone = s.replyTone, seed = seed())
            ?.let { Suggestion(it, 0.0, builtin = true) } ?: return own
        val sure = own.any { it.score >= ReplyIndex.AUTO_MIN }
        val merged = if (sure) own + composed else listOf(composed) + own
        return merged.distinctBy { ReplyText.replyKey(it.reply) }.take(limit)
    }

    /** The reply sent automatically, only when confident enough. */
    suspend fun best(incoming: String): String? {
        if (!settings.current().learnReplies) return null
        return index().best(incoming, System.currentTimeMillis())?.reply
    }

    /** What the auto-reply does with an incoming text. */
    sealed interface Answer {
        data class Send(val text: String) : Answer
        /** Nothing should be sent at all (an "ok", an answer to the user's own question, a second greeting). */
        data object Silent : Answer
        /** The engine has nothing to say: the fixed text applies. */
        data object Unknown : Answer
    }

    /**
     * Decides the automatic answer to [incoming] in [threadId]: the user's habitual reply when one
     * surely fits, otherwise a reply written for what the text says, in the chosen tone.
     */
    suspend fun answer(incoming: String, threadId: Long): Answer {
        if (ReplyIntents.needsNoReply(incoming)) return Answer.Silent
        val s = settings.current()
        val now = System.currentTimeMillis()
        // Newest first; the first row is the incoming text itself.
        val before = messages.recentInThread(threadId, CONTEXT_DEPTH).dropWhile { it.box == Box.INBOX }
        val previous = before.firstOrNull()
        val answersMe = previous != null && !previous.autoReply && now - previous.date < CONTEXT_MS &&
            (previous.body.contains('?') || previous.body.contains('؟') || ReplyIntents.asks(previous.body))
        val said = ReplyIntents.all(incoming)
        if (answersMe && said.isEmpty()) return Answer.Silent
        val greeted = before.any { it.box != Box.INBOX && now - it.date < GREETED_MS && ReplyIntents.greets(it.body) }
        if (greeted && ReplyIntents.onlyGreets(incoming)) return Answer.Silent
        if (!ReplyIntents.isGrave(incoming)) best(incoming)?.let { return Answer.Send(it) }
        val address = if (s.learnReplies) index().addressTerm else null
        val composed = ReplyIntents.reply(
            incoming, address, tone = s.replyTone, greeted = greeted,
            hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY), seed = seed(),
        )
        return if (composed != null) Answer.Send(composed) else Answer.Unknown
    }

    private fun seed(): Int = (System.currentTimeMillis() / 3_600_000L).toInt()

    /**
     * For the "try it" dialog, learning switch aside: the user's own replies first; when none is
     * confident enough, the reply written for the text (marked [Suggestion.builtin]).
     */
    suspend fun preview(incoming: String): List<Suggestion> {
        val index = index()
        val own = index.suggest(incoming, 3, System.currentTimeMillis(), withPopular = false)
        if (own.any { it.score >= ReplyIndex.AUTO_MIN } && !ReplyIntents.isGrave(incoming)) return own
        val builtin = ReplyIntents.reply(incoming, index.addressTerm, tone = settings.current().replyTone, seed = seed())
            ?.let { Suggestion(it, 0.0, builtin = true) }
        return if (builtin == null) own else listOf(builtin) + own
    }

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
        const val CONTEXT_DEPTH = 8
        const val CONTEXT_MS = 12 * 60 * 60_000L
        const val GREETED_MS = 6 * 60 * 60_000L
    }
}
