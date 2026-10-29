package dev.saged.barq.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.data.ConversationRepository
import dev.saged.barq.data.ProviderLock
import dev.saged.barq.data.contacts.ContactsRepository
import dev.saged.barq.data.db.AutoReplyRuleEntity
import dev.saged.barq.data.db.BarqDatabase
import dev.saged.barq.data.db.BlockedKeywordEntity
import dev.saged.barq.data.db.BlockedNumberEntity
import dev.saged.barq.data.db.Box
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.CategoryRuleEntity
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.db.SendGroupEntity
import dev.saged.barq.data.db.SendGroupMemberEntity
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.AutoReplyMode
import dev.saged.barq.data.settings.BackupFrequency
import dev.saged.barq.data.settings.SwipeAction
import dev.saged.barq.data.settings.ThemeMode
import dev.saged.barq.data.db.TemplateEntity
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.telephony.TelephonyProvider
import dev.saged.barq.domain.Classifier
import dev.saged.barq.domain.OtpDetector
import dev.saged.barq.domain.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupMessage(
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long = 0,
    val box: Int,
    val read: Boolean = true,
    val subId: Int = -1,
)

@Serializable
data class BackupConversation(
    val addresses: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val category: String = Category.PERSONAL.name,
    val categoryLocked: Boolean = false,
)

@Serializable
data class BackupRule(val sender: String, val category: String)

@Serializable
data class BackupAutoReply(val keywords: String, val reply: String, val enabled: Boolean = true)

/** Settings the user chose (technical state like the backup folder is not included). */
@Serializable
data class BackupSettings(
    val themeMode: String = ThemeMode.SYSTEM.name,
    val dynamicColor: Boolean = true,
    val fontScale: Float = 1f,
    val datePattern: String = "dd/MM/yyyy",
    val use24h: Boolean = true,
    val deliveryReports: Boolean = false,
    val showCounter: Boolean = true,
    val mmsEnabled: Boolean = false,
    val spamFilter: Boolean = true,
    val recycleBin: Boolean = true,
    val swipeStart: String = SwipeAction.ARCHIVE.name,
    val swipeEnd: String = SwipeAction.DELETE.name,
    val defaultSubId: Int = -1,
    val sendDelayMs: Long = 250,
    val autoReplyEnabled: Boolean = false,
    val autoReplyMode: String = AutoReplyMode.FIXED.name,
    val autoReplyText: String = "",
    val autoReplyContactsOnly: Boolean = false,
    val autoReplyCooldownMin: Int = 60,
    val autoReplyWindow: Boolean = false,
    val autoReplyFromMin: Int = 22 * 60,
    val autoReplyToMin: Int = 7 * 60,
    val backupFrequency: String = BackupFrequency.OFF.name,
)

@Serializable
data class BackupGroupMember(val address: String, val name: String? = null)

@Serializable
data class BackupGroup(val name: String, val members: List<BackupGroupMember> = emptyList())

@Serializable
data class BackupFile(
    val format: String = "barq-backup",
    val version: Int = 2,
    val createdAt: Long = 0,
    val blockedNumbers: List<String> = emptyList(),
    val blockedKeywords: List<String> = emptyList(),
    val rules: List<BackupRule> = emptyList(),
    val templates: List<String> = emptyList(),
    val autoReplyRules: List<BackupAutoReply> = emptyList(),
    val conversations: List<BackupConversation> = emptyList(),
    val messages: List<BackupMessage> = emptyList(),
    val groups: List<BackupGroup> = emptyList(),
    val settings: BackupSettings? = null,
)

/** [settings] is set when the file carries settings: the screen asks before applying them. */
data class ImportResult(val imported: Int, val skipped: Int, val settings: AppSettings? = null)

private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T =
    runCatching { enumValueOf<T>(name) }.getOrDefault(default)

fun AppSettings.toBackup() = BackupSettings(
    themeMode.name, dynamicColor, fontScale, datePattern, use24h, deliveryReports, showCounter, mmsEnabled,
    spamFilter, recycleBin, swipeStart.name, swipeEnd.name, defaultSubId, sendDelayMs, autoReplyEnabled,
    autoReplyMode.name, autoReplyText, autoReplyContactsOnly, autoReplyCooldownMin, autoReplyWindow,
    autoReplyFromMin, autoReplyToMin, backupFrequency.name,
)

fun BackupSettings.toSettings(base: AppSettings) = base.copy(
    themeMode = enumOr(themeMode, base.themeMode),
    dynamicColor = dynamicColor,
    fontScale = fontScale,
    datePattern = datePattern,
    use24h = use24h,
    deliveryReports = deliveryReports,
    showCounter = showCounter,
    mmsEnabled = mmsEnabled,
    spamFilter = spamFilter,
    recycleBin = recycleBin,
    swipeStart = enumOr(swipeStart, base.swipeStart),
    swipeEnd = enumOr(swipeEnd, base.swipeEnd),
    defaultSubId = defaultSubId,
    sendDelayMs = sendDelayMs,
    autoReplyEnabled = autoReplyEnabled,
    autoReplyMode = enumOr(autoReplyMode, base.autoReplyMode),
    autoReplyText = autoReplyText.ifBlank { base.autoReplyText },
    autoReplyContactsOnly = autoReplyContactsOnly,
    autoReplyCooldownMin = autoReplyCooldownMin,
    autoReplyWindow = autoReplyWindow,
    autoReplyFromMin = autoReplyFromMin,
    autoReplyToMin = autoReplyToMin,
    backupFrequency = enumOr(backupFrequency, base.backupFrequency),
)

/** JSON export/import of messages + Barq settings lists, and automatic scheduled backups. */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: BarqDatabase,
    private val provider: TelephonyProvider,
    private val conversations: ConversationRepository,
    private val lock: ProviderLock,
    private val contacts: ContactsRepository,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Streams the backup so even very large histories don't need much memory. */
    suspend fun export(out: OutputStream): Int = withContext(Dispatchers.IO) {
        var count = 0
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            val blocks = db.blocks()
            w.write("{\"format\":\"barq-backup\",\"version\":2,\"createdAt\":${System.currentTimeMillis()}")
            w.write(",\"settings\":" + json.encodeToString(settings.current().toBackup()))
            val groupDao = db.groups()
            w.write(",\"groups\":" + json.encodeToString(groupDao.all().map { g ->
                BackupGroup(g.name, groupDao.members(g.id).map { BackupGroupMember(it.address, it.name) })
            }))
            w.write(",\"blockedNumbers\":" + json.encodeToString(blocks.numbersOnce().map { it.number }))
            w.write(",\"blockedKeywords\":" + json.encodeToString(blocks.keywordsOnce().map { it.keyword }))
            w.write(",\"rules\":" + json.encodeToString(db.rules().all().map { BackupRule(it.sender, it.category.name) }))
            w.write(",\"templates\":" + json.encodeToString(db.templates().all().map { it.text }))
            w.write(",\"autoReplyRules\":" + json.encodeToString(db.autoReply().allRules().map { BackupAutoReply(it.keywords, it.reply, it.enabled) }))
            w.write(",\"conversations\":" + json.encodeToString(db.conversations().all().map {
                BackupConversation(it.addresses, it.pinned, it.archived, it.muted, it.category.name, it.categoryLocked)
            }))
            w.write(",\"messages\":[")
            var offset = 0
            var first = true
            while (true) {
                val page = db.messages().page(PAGE, offset)
                if (page.isEmpty()) break
                page.filter { it.box != Box.QUEUED }.forEach { m ->
                    if (!first) w.write(",")
                    first = false
                    w.write(json.encodeToString(BackupMessage(m.address, m.body, m.date, m.dateSent, m.box, m.read, m.subId)))
                    count++
                }
                offset += page.size
            }
            w.write("]}")
        }
        count
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val backup = context.contentResolver.openInputStream(uri)?.use { json.decodeFromStream<BackupFile>(it) }
            ?: return@withContext ImportResult(0, 0)
        restoreLists(backup)
        restoreGroups(backup)
        var imported = 0
        var skipped = 0
        val rules = db.rules().all()
        val spamFilter = settings.current().spamFilter
        val touched = HashSet<Long>()
        val messages = db.messages()
        val resolver = conversations.threadResolver()
        // Fast duplicate check: one read of (number|time) keys; the text is compared only on a match.
        val keys = messages.allKeys().toHashSet()
        backup.messages.chunked(PAGE).forEach { chunk ->
            lock.withLock {
                val toInsert = ArrayList<MessageEntity>(chunk.size)
                chunk.forEach { m ->
                    val key = m.address + "|" + m.date
                    if (key in keys && messages.countSame(m.address, m.date, m.body) > 0) {
                        skipped++
                        return@forEach
                    }
                    keys += key
                    val box = if (m.box == Box.INBOX || m.box == Box.SENT) m.box else Box.FAILED
                    val threadId = resolver.resolve(m.address)
                    val providerId = provider.insertSms(
                        address = m.address, body = m.body, date = m.date, dateSent = m.dateSent,
                        type = box, read = m.read, threadId = threadId, subId = m.subId,
                    )
                    val incoming = box == Box.INBOX
                    val otp = if (incoming) OtpDetector.detect(m.body) else null
                    val category = if (incoming) {
                        Classifier.classify(m.address, m.body, otp, contacts.isContact(m.address), spamFilter, rules)
                    } else Category.PERSONAL
                    toInsert += MessageEntity(
                        providerId = providerId, threadId = threadId, address = m.address, body = m.body,
                        date = m.date, dateSent = m.dateSent, box = box, read = m.read || !incoming,
                        subId = m.subId, otp = otp, category = category,
                    )
                    touched += threadId
                    imported++
                }
                db.withTransaction { messages.insertAll(toInsert) }
            }
        }
        conversations.refreshMany(touched)
        conversations.mergeDuplicateConversations()
        restoreConversationFlags(backup)
        ImportResult(imported, skipped, backup.settings?.toSettings(settings.current()))
    }

    private suspend fun restoreLists(b: BackupFile) {
        val blocks = db.blocks()
        b.blockedNumbers.forEach { blocks.addNumber(BlockedNumberEntity(it)) }
        b.blockedKeywords.forEach { blocks.addKeyword(BlockedKeywordEntity(it)) }
        val existingRules = db.rules().all()
        b.rules.forEach { r ->
            val cat = runCatching { Category.valueOf(r.category) }.getOrNull() ?: return@forEach
            val id = existingRules.firstOrNull { PhoneUtils.same(it.sender, r.sender) }?.id ?: 0
            db.rules().upsert(CategoryRuleEntity(id = id, sender = r.sender, category = cat))
        }
        val templates = db.templates()
        val existingTemplates = templates.all().map { it.text }.toSet()
        b.templates.filter { it !in existingTemplates }.forEachIndexed { i, t ->
            templates.upsert(TemplateEntity(text = t, position = existingTemplates.size + i))
        }
        val ar = db.autoReply()
        val existingAr = ar.allRules().map { it.keywords to it.reply }.toSet()
        b.autoReplyRules.filter { (it.keywords to it.reply) !in existingAr }.forEach {
            ar.upsertRule(AutoReplyRuleEntity(keywords = it.keywords, reply = it.reply, enabled = it.enabled))
        }
    }

    /** Groups are merged by name: members are added, nothing is removed. */
    private suspend fun restoreGroups(b: BackupFile) {
        if (b.groups.isEmpty()) return
        val dao = db.groups()
        val existing = dao.all()
        b.groups.filter { it.name.isNotBlank() }.forEach { g ->
            val id = existing.firstOrNull { it.name == g.name }?.id ?: dao.insert(SendGroupEntity(name = g.name))
            dao.addMembers(g.members.map { SendGroupMemberEntity(groupId = id, address = it.address, name = it.name) })
        }
    }

    /** Applies settings from a backup after the user agreed. */
    suspend fun applySettings(s: AppSettings) = settings.restore(s)

    private suspend fun restoreConversationFlags(b: BackupFile) {
        val dao = db.conversations()
        val resolver = conversations.threadResolver()
        b.conversations.forEach { c ->
            val threadId = resolver.resolve(c.addresses)
            val conv = dao.get(threadId) ?: return@forEach
            val cat = runCatching { Category.valueOf(c.category) }.getOrDefault(conv.category)
            dao.upsert(
                conv.copy(
                    pinned = c.pinned, archived = c.archived, muted = c.muted,
                    category = if (c.categoryLocked) cat else conv.category,
                    categoryLocked = c.categoryLocked,
                )
            )
        }
    }

    // ------------------------------------------------------------------ scheduled backups

    fun backupFileName(): String =
        "barq-backup-" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".json"

    /** Writes a backup into the user's chosen folder, or the app's own folder as a fallback. */
    suspend fun runAutomaticBackup(): Boolean = withContext(Dispatchers.IO) {
        val s = settings.current()
        val name = backupFileName()
        val ok = runCatching {
            val tree = s.backupTreeUri?.let(Uri::parse)
            if (tree != null) {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(context.contentResolver, parent, "application/json", name)
                    ?: error("cannot create file")
                context.contentResolver.openOutputStream(doc)?.use { export(it) } ?: error("cannot open file")
            } else {
                val dir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
                File(dir, name).outputStream().use { export(it) }
                // Keep the 5 most recent automatic backups.
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
            }
        }.isSuccess
        if (ok) settings.setLastBackupAt(System.currentTimeMillis())
        ok
    }

    private companion object {
        const val PAGE = 500
    }
}
