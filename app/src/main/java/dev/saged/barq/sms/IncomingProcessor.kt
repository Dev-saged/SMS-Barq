package dev.saged.barq.sms

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.data.ConversationRepository
import dev.saged.barq.data.IncomingRepository
import dev.saged.barq.data.db.Attachment
import dev.saged.barq.data.db.Category
import dev.saged.barq.domain.AutoReplyEngine
import dev.saged.barq.notifications.ActiveThread
import dev.saged.barq.notifications.Notifier
import dev.saged.barq.util.CrashLog
import dev.saged.barq.widget.OtpWidget
import javax.inject.Inject
import javax.inject.Singleton

/** Everything that happens after a message arrives: store → notify → widget → auto-reply. */
@Singleton
class IncomingProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val incoming: IncomingRepository,
    private val conversations: ConversationRepository,
    private val notifier: Notifier,
    private val autoReply: AutoReplyEngine,
) {
    suspend fun onSms(address: String, body: String, dateSent: Long, subId: Int) {
        val stored = incoming.onIncomingSms(address, body, dateSent, subId) ?: return
        after(stored)
    }

    suspend fun onMms(address: String, text: String, subject: String?, attachments: List<Attachment>, date: Long, subId: Int) {
        val stored = incoming.onIncomingMms(address, text, subject, attachments, date, subId) ?: return
        after(stored)
    }

    private suspend fun after(stored: IncomingRepository.Incoming) {
        val (message, conversation) = stored
        when {
            // The conversation is open on screen: it is read already, no sound, no notification.
            ActiveThread.isOpen(conversation.threadId) -> conversations.markRead(listOf(conversation.threadId))
            // Spam is stored silently; muted conversations never ring.
            conversation.category != Category.SPAM && !conversation.muted ->
                notifier.notifyConversation(conversation.threadId)
        }
        if (message.otp != null) runCatching { OtpWidget.refresh(context) }
        if (conversation.category != Category.SPAM) {
            runCatching { autoReply.maybeReply(message) }.onFailure { CrashLog.warn("auto-reply", it) }
        }
    }
}
