package dev.saged.barq.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.data.contacts.ContactsRepository
import dev.saged.barq.data.db.BarqDatabase
import dev.saged.barq.data.db.Box
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.telephony.ProviderSms
import dev.saged.barq.data.telephony.TelephonyProvider
import dev.saged.barq.domain.Classifier
import dev.saged.barq.domain.OtpDetector
import dev.saged.barq.domain.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports the phone's existing SMS history into Barq (first run) and picks up anything another
 * app wrote later (incremental, by provider _id). Runs in pages inside transactions: fast even
 * with tens of thousands of messages.
 */
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: BarqDatabase,
    private val provider: TelephonyProvider,
    private val conversations: ConversationRepository,
    private val lock: ProviderLock,
    private val contacts: ContactsRepository,
    private val settings: SettingsRepository,
) {
    data class Progress(val running: Boolean = false, val imported: Int = 0)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    private fun canRead() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    suspend fun sync() = withContext(Dispatchers.IO) {
        if (!canRead() || _progress.value.running) return@withContext
        _progress.value = Progress(running = true)
        val messages = db.messages()
        val rules = db.rules().all()
        val spamFilter = settings.current().spamFilter
        val touched = HashSet<Long>()
        var imported = 0
        // One conversation per real number: +972…, +970… and 0… of the same number share a thread.
        val keyToThread = HashMap<String, Long>()
        val threadToKey = HashMap<Long, String>()
        db.conversations().all().sortedByDescending { it.date }.forEach { conv ->
            val key = PhoneUtils.conversationKey(conv.addresses)
            if (keyToThread.putIfAbsent(key, conv.threadId) == null) threadToKey.putIfAbsent(conv.threadId, key)
        }
        fun canonicalThread(sms: ProviderSms): Long {
            if (sms.address.isBlank()) return sms.threadId
            val key = PhoneUtils.matchKey(sms.address)
            keyToThread[key]?.let { return it }
            var thread = sms.threadId
            val owner = threadToKey[thread]
            // A phone "group" thread holds several numbers: give each number its own conversation.
            if (owner != null && owner != key) thread = provider.threadIdFor(listOf(sms.address))
            keyToThread[key] = thread
            threadToKey.putIfAbsent(thread, key)
            return thread
        }
        try {
            lock.withLock {
                val after = messages.maxProviderId() ?: 0L
                provider.readSmsAfter(after, PAGE) { page ->
                    val entities = page.map { it.toEntity(canonicalThread(it), rules, spamFilter) }
                    db.withTransaction { messages.insertAll(entities) }
                    entities.forEach { touched += it.threadId }
                    imported += page.size
                    _progress.value = Progress(running = true, imported = imported)
                }
            }
            // Nothing new (the usual case when the app is opened): no extra work at all.
            if (imported > 0) {
                conversations.refreshMany(touched)
                conversations.mergeDuplicateConversations()
                conversations.refreshNames(rebuildIndex = false)
            }
        } finally {
            _progress.value = Progress(running = false, imported = imported)
        }
    }

    private fun ProviderSms.toEntity(
        canonicalThreadId: Long,
        rules: List<dev.saged.barq.data.db.CategoryRuleEntity>,
        spamFilter: Boolean,
    ): MessageEntity {
        val incoming = type == Box.INBOX
        val otp = if (incoming) OtpDetector.detect(body) else null
        val category = if (incoming) {
            Classifier.classify(address, body, otp, contacts.isContact(address), spamFilter, rules)
        } else Category.PERSONAL
        // Never re-send another app's pending messages: anything unsent is shown as failed.
        val box = when (type) {
            Box.INBOX, Box.SENT, Box.FAILED -> type
            Box.OUTBOX, Box.QUEUED -> Box.FAILED
            else -> Box.SENT
        }
        return MessageEntity(
            providerId = id, threadId = canonicalThreadId, address = address, body = body, date = date,
            dateSent = dateSent, box = box, read = read || !incoming, subId = subId, otp = otp,
            category = category,
        )
    }

    private companion object {
        const val PAGE = 500
    }
}
