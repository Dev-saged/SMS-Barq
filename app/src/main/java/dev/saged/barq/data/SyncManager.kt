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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
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
    private val repository: MessageRepository,
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
        try {
            repository.providerLock.withLock {
                val after = messages.maxProviderId() ?: 0L
                provider.readSmsAfter(after, PAGE) { page ->
                    val entities = page.map { it.toEntity(rules, spamFilter) }
                    db.withTransaction { messages.insertAll(entities) }
                    page.forEach { touched += it.threadId }
                    imported += page.size
                    _progress.value = Progress(running = true, imported = imported)
                }
            }
            touched.forEach { repository.refreshConversation(it) }
        } finally {
            _progress.value = Progress(running = false, imported = imported)
        }
    }

    private fun ProviderSms.toEntity(rules: List<dev.saged.barq.data.db.CategoryRuleEntity>, spamFilter: Boolean): MessageEntity {
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
            providerId = id, threadId = threadId, address = address, body = body, date = date,
            dateSent = dateSent, box = box, read = read || !incoming, subId = subId, otp = otp,
            category = category,
        )
    }

    private companion object {
        const val PAGE = 500
    }
}
