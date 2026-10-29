package dev.saged.barq.sms

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.data.MessageRepository
import dev.saged.barq.data.db.Attachment
import dev.saged.barq.data.db.Category
import dev.saged.barq.domain.AutoReplyEngine
import dev.saged.barq.notifications.Notifier
import dev.saged.barq.widget.OtpWidget
import javax.inject.Inject
import javax.inject.Singleton

/** Everything that happens after a message arrives: store → notify → widget → auto-reply. */
@Singleton
class IncomingProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MessageRepository,
    private val notifier: Notifier,
    private val autoReply: AutoReplyEngine,
) {
    suspend fun onSms(address: String, body: String, dateSent: Long, subId: Int) {
        val incoming = repository.onIncomingSms(address, body, dateSent, subId) ?: return
        after(incoming)
    }

    suspend fun onMms(address: String, text: String, subject: String?, attachments: List<Attachment>, date: Long, subId: Int) {
        val incoming = repository.onIncomingMms(address, text, subject, attachments, date, subId) ?: return
        after(incoming)
    }

    private suspend fun after(incoming: MessageRepository.Incoming) {
        val (message, conversation) = incoming
        // Spam is stored silently; muted conversations never ring.
        if (conversation.category != Category.SPAM && !conversation.muted) {
            notifier.notifyConversation(conversation.threadId)
        }
        if (message.otp != null) runCatching { OtpWidget.refresh(context) }
        if (conversation.category != Category.SPAM) {
            runCatching { autoReply.maybeReply(message) }
        }
    }
}
