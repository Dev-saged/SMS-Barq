package dev.saged.barq.data

import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.data.contacts.ContactsRepository
import dev.saged.barq.data.db.Attachment
import dev.saged.barq.data.db.BarqDatabase
import dev.saged.barq.data.db.Box
import dev.saged.barq.data.db.ConversationEntity
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.telephony.TelephonyProvider
import dev.saged.barq.domain.Classifier
import dev.saged.barq.domain.OtpDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Stores incoming SMS and MMS: block check → OTP → category → system provider + Room. */
@Singleton
class IncomingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: BarqDatabase,
    private val provider: TelephonyProvider,
    private val contacts: ContactsRepository,
    private val settings: SettingsRepository,
    private val blockList: BlockList,
    private val conversations: ConversationRepository,
    private val lock: ProviderLock,
) {
    private val messages = db.messages()
    private val rules = db.rules()

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
            val stored = lock.withLock {
                val threadId = conversations.threadResolver().resolve(address)
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
            val conv = conversations.refresh(stored.threadId, revive = true) ?: return@withContext null
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
        val stored = lock.withLock {
            val threadId = conversations.threadResolver().resolve(address)
            val msg = MessageEntity(
                threadId = threadId, address = address, body = text, date = date, dateSent = date,
                box = Box.INBOX, read = false, subId = subId, otp = otp, category = category,
                isMms = true, attachments = attachments, subject = subject,
            )
            msg.copy(id = messages.insert(msg))
        }
        val conv = conversations.refresh(stored.threadId, revive = true) ?: return@withContext null
        Incoming(stored, conv)
    }

    fun attachmentDir(): File = File(context.filesDir, "attachments").apply { mkdirs() }
}
