package dev.saged.barq.domain

import dev.saged.barq.data.BlockList
import dev.saged.barq.data.contacts.ContactsRepository
import dev.saged.barq.data.db.AutoReplyDao
import dev.saged.barq.data.db.AutoReplyLogEntity
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.db.isIncoming
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.AutoReplyMode
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.sms.SendEngine
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "I'm busy" auto-reply. Off by default, switched on by the user when needed.
 *  - FIXED: same text to everyone.
 *  - LEARNED: answers the way the user usually answers such a text ([ReplyEngine], on this phone
 *             only), falling back to the fixed text when no past reply fits well enough.
 * Safety: never replies to service senders/short codes, and at most once per sender per cooldown,
 * so two phones can never get stuck replying to each other.
 */
@Singleton
class AutoReplyEngine @Inject constructor(
    private val settings: SettingsRepository,
    private val dao: AutoReplyDao,
    private val contacts: ContactsRepository,
    private val blockList: BlockList,
    private val engine: dagger.Lazy<SendEngine>,
    private val replies: ReplyEngine,
) {
    suspend fun maybeReply(message: MessageEntity) {
        val s = settings.current()
        if (!s.autoReplyEnabled || !message.isIncoming || message.autoReply) return
        val address = message.address
        if (!PhoneUtils.isReplyable(address)) return
        if (blockList.isBlockedNumber(address)) return
        if (s.autoReplyContactsOnly && !contacts.isContact(address)) return
        if (s.autoReplyWindow && !inWindow(s)) return

        val key = PhoneUtils.normalize(address)
        val last = dao.lastReply(key)
        val now = System.currentTimeMillis()
        if (last != null && now - last.lastAt < s.autoReplyCooldownMin * 60_000L) return

        val reply = pickReply(message, s) ?: return
        dao.logReply(AutoReplyLogEntity(key, now))
        engine.get().sendAutoReply(address, reply, message.subId)
    }

    private suspend fun pickReply(message: MessageEntity, s: AppSettings): String? {
        if (s.autoReplyMode == AutoReplyMode.LEARNED) {
            if (message.category != Category.PERSONAL) return null
            replies.best(message.body)?.let { return it }
        }
        return s.autoReplyText.takeIf { it.isNotBlank() }
    }

    private fun inWindow(s: AppSettings): Boolean {
        val c = Calendar.getInstance()
        val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val from = s.autoReplyFromMin
        val to = s.autoReplyToMin
        return if (from <= to) minute in from until to else minute >= from || minute < to
    }
}
