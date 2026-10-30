package dev.saged.barq.data

import android.content.Context
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.R
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.domain.ReactionText
import dev.saged.barq.sms.SendEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emoji reactions on messages. My reaction is stored on the message (and, if the user wants,
 * also sent as a short SMS). A reaction text that arrives from the other person is recognised
 * and shown on the message it points at, instead of as a new bubble.
 */
@Singleton
class ReactionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messages: MessageDao,
    private val settings: SettingsRepository,
    private val conversations: ConversationRepository,
    private val engine: Lazy<SendEngine>,
) {
    /** Sets (or, when it is the same emoji again, removes) my reaction to [messageId]. */
    suspend fun react(messageId: Long, emoji: String) = withContext(Dispatchers.IO) {
        val msg = messages.get(messageId) ?: return@withContext
        val newValue = if (msg.reaction == emoji) null else emoji
        messages.setReaction(messageId, newValue)
        if (newValue != null && settings.current().reactionsAsSms) {
            val conv = conversations.get(msg.threadId) ?: return@withContext
            if (PhoneUtils.splitAddresses(conv.addresses).all { PhoneUtils.isPhoneNumber(it) }) {
                val arabic = context.resources.getBoolean(R.bool.barq_rtl)
                engine.get().sendReaction(msg.threadId, ReactionText.outgoing(newValue, msg.body, arabic), msg.id)
            }
        }
    }

    /**
     * Called for every incoming SMS. Returns true when it was a reaction to a message of this
     * conversation: it is then attached to that message and hidden from the conversation.
     */
    suspend fun onIncoming(message: MessageEntity): Boolean = withContext(Dispatchers.IO) {
        val parsed = ReactionText.parse(message.body) ?: return@withContext false
        val target = messages.recentInThread(message.threadId, SEARCH_DEPTH)
            .firstOrNull { it.id != message.id && ReactionText.matches(parsed.quoted, it.body) }
            ?: return@withContext false
        messages.setTheirReaction(target.id, parsed.emoji)
        messages.setReactionTo(message.id, target.id)
        conversations.refresh(message.threadId)
        true
    }

    private companion object {
        const val SEARCH_DEPTH = 300
    }
}
