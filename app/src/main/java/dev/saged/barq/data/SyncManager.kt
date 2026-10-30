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
import dev.saged.barq.data.db.DeliveryStatus
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports the phone's existing SMS history into Barq (first run) and picks up anything another
 * app wrote later (incremental, by provider _id). Runs in pages inside transactions: fast even
 * with tens of thousands of messages.
 *
 * Messages another app sends (a gateway, a bot…) are written to the system store by Android
 * itself, first as "sending" and later as sent or failed. They are imported as they appear and
 * then followed by [reconcile] until their result is known, so they never show a wrong state.
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

    private val mutex = Mutex()
    private val messages get() = db.messages()

    private fun canRead() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Several callers (app opened, live watcher) take turns; nothing new is ever missed. */
    suspend fun sync() = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext
        mutex.withLock { syncLocked() }
    }

    private suspend fun syncLocked() {
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
        var firstImport = false
        try {
            lock.withLock {
                val after = messages.maxProviderId() ?: 0L
                // First import = the phone's history. After that, anything Barq did not write itself
                // (Barq records its own rows) was sent by another app.
                firstImport = after == 0L
                if (firstImport) _progress.value = Progress(running = true)
                provider.readSmsAfter(after, PAGE) { page ->
                    val entities = page.map { it.toEntity(canonicalThread(it), rules, spamFilter, !firstImport) }
                    db.withTransaction { messages.insertAll(entities) }
                    entities.forEach { touched += it.threadId }
                    imported += page.size
                    if (firstImport) _progress.value = Progress(running = true, imported = imported)
                }
            }
            // Nothing new (the usual case when the app is opened): no extra work at all.
            if (imported > 0) {
                conversations.refreshMany(touched)
                // The heavy clean-up is only needed for the phone's history; live imports already
                // go to the right conversation.
                if (firstImport) {
                    conversations.mergeDuplicateConversations()
                    conversations.refreshNames(rebuildIndex = false)
                }
            }
        } finally {
            if (firstImport) _progress.value = Progress(running = false, imported = imported)
        }
    }

    /**
     * Brings other apps' messages up to date with the system store (sending → sent / failed /
     * delivered). [repairFailed] also re-checks every failed message: one that actually went out
     * is shown as sent (fixes counts from older versions, which marked them failed on import).
     */
    suspend fun reconcile(repairFailed: Boolean = false) = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext
        mutex.withLock {
            val ids = ArrayList<Long>(messages.externalPendingProviderIds())
            if (repairFailed) ids += messages.failedProviderIds()
            if (ids.isEmpty()) return@withLock
            val states = provider.readStates(ids).associateBy { it.providerId }
            val touched = HashSet<Long>()
            ids.distinct().chunked(500).forEach { chunk ->
                val rows = messages.byProviderIds(chunk)
                db.withTransaction {
                    rows.forEach { m ->
                        val st = m.providerId?.let { states[it] } ?: return@forEach
                        val box = when (st.type) {
                            Box.SENT -> Box.SENT
                            Box.FAILED -> Box.FAILED
                            Box.OUTBOX, Box.QUEUED -> Box.OUTBOX
                            else -> return@forEach
                        }
                        // A failed row is only ever corrected to "sent", never the other way round.
                        if (m.box == Box.FAILED && box != Box.SENT) return@forEach
                        if (!m.fromOtherApp && m.box != Box.FAILED) return@forEach
                        val delivery = deliveryOf(st.status, m.deliveryStatus)
                        val error = if (box == Box.FAILED) st.errorCode.coerceAtLeast(0) else 0
                        if (box == m.box && delivery == m.deliveryStatus && error == m.errorCode) return@forEach
                        messages.applyProviderState(m.id, box, error, delivery)
                        touched += m.threadId
                    }
                }
            }
            if (touched.isNotEmpty()) conversations.refreshMany(touched)
        }
    }

    private fun deliveryOf(status: Int, current: Int): Int = when (status) {
        android.provider.Telephony.Sms.STATUS_COMPLETE -> DeliveryStatus.DELIVERED
        android.provider.Telephony.Sms.STATUS_FAILED -> DeliveryStatus.FAILED
        android.provider.Telephony.Sms.STATUS_PENDING -> DeliveryStatus.PENDING
        else -> current
    }

    private fun ProviderSms.toEntity(
        canonicalThreadId: Long,
        rules: List<dev.saged.barq.data.db.CategoryRuleEntity>,
        spamFilter: Boolean,
        live: Boolean,
    ): MessageEntity {
        val incoming = type == Box.INBOX
        val otp = if (incoming) OtpDetector.detect(body) else null
        val category = if (incoming) {
            Classifier.classify(address, body, otp, contacts.isContact(address), spamFilter, rules)
        } else Category.PERSONAL
        // Barq never re-sends another app's messages. One still on its way is followed live
        // (OUTBOX + fromOtherApp) until Android reports the result; an old one never will be.
        val external = live && !incoming
        val box = when (type) {
            Box.INBOX, Box.SENT, Box.FAILED -> type
            Box.OUTBOX, Box.QUEUED -> if (external) Box.OUTBOX else Box.FAILED
            else -> Box.SENT
        }
        return MessageEntity(
            providerId = id, threadId = canonicalThreadId, address = address, body = body, date = date,
            dateSent = dateSent, box = box, read = read || !incoming, subId = subId, otp = otp,
            category = category, fromOtherApp = external,
            deliveryStatus = if (incoming) DeliveryStatus.NONE else deliveryOf(status, DeliveryStatus.NONE),
        )
    }

    private companion object {
        const val PAGE = 500
    }
}
