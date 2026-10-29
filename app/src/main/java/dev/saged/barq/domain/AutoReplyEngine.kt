package dev.saged.barq.domain

import dev.saged.barq.data.BlockList
import dev.saged.barq.data.MessageRepository
import dev.saged.barq.data.contacts.ContactsRepository
import dev.saged.barq.data.db.AutoReplyDao
import dev.saged.barq.data.db.AutoReplyLogEntity
import dev.saged.barq.data.db.AutoReplyRuleEntity
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.db.isIncoming
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.AutoReplyMode
import dev.saged.barq.data.settings.SettingsRepository
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "I'm busy" auto-reply. Off by default, switched on by the user when needed.
 *  - FIXED: same text to everyone.
 *  - SMART: reads the incoming text and picks the matching rule (greeting, question, urgent…),
 *           falling back to the fixed text.
 * Safety: never replies to service senders/short codes, and at most once per sender per cooldown,
 * so two phones can never get stuck replying to each other.
 */
@Singleton
class AutoReplyEngine @Inject constructor(
    private val settings: SettingsRepository,
    private val dao: AutoReplyDao,
    private val contacts: ContactsRepository,
    private val blockList: BlockList,
    private val repository: dagger.Lazy<MessageRepository>,
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

        val reply = pickReply(message.body, s) ?: return
        dao.logReply(AutoReplyLogEntity(key, now))
        repository.get().queueOutgoing(listOf(address), reply, message.subId, autoReply = true)
    }

    suspend fun pickReply(incoming: String, s: AppSettings): String? {
        if (s.autoReplyMode == AutoReplyMode.SMART) {
            val text = PhoneUtils.normalizeDigits(incoming).lowercase()
            matchRule(text, dao.enabledRules())?.let { return it.reply }
        }
        return s.autoReplyText.takeIf { it.isNotBlank() }
    }

    private fun matchRule(text: String, rules: List<AutoReplyRuleEntity>): AutoReplyRuleEntity? =
        rules.firstOrNull { rule ->
            rule.keywords.split('|', ',', '،')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .any { text.contains(it) }
        }

    private fun inWindow(s: AppSettings): Boolean {
        val c = Calendar.getInstance()
        val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val from = s.autoReplyFromMin
        val to = s.autoReplyToMin
        return if (from <= to) minute in from until to else minute >= from || minute < to
    }

    companion object {
        /** Seeded on first run so SMART mode is useful immediately. */
        val defaultRules = listOf(
            AutoReplyRuleEntity(keywords = "السلام عليكم|سلام عليكم", reply = "وعليكم السلام، أنا مشغول حالياً وسأرد عليك قريباً."),
            AutoReplyRuleEntity(keywords = "مستعجل|ضروري|عاجل|urgent", reply = "مشغول الآن، إذا الأمر عاجل اتصل بي من فضلك."),
            AutoReplyRuleEntity(keywords = "وين|متى|كيف|شو|ليش|؟|?", reply = "مشغول حالياً، سأرد على سؤالك بأقرب وقت."),
            AutoReplyRuleEntity(keywords = "مرحبا|هلا|اهلا|أهلا|hi|hello", reply = "أهلاً، أنا مشغول الآن وسأتواصل معك لاحقاً."),
        )
    }
}
