package dev.saged.barq.data.db

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.saged.barq.domain.TextNorm
import kotlinx.serialization.Serializable

/** Message categories used for the inbox filters. */
enum class Category { PERSONAL, OTP, BANK, OFFERS, SPAM }

/** Mirrors android.provider.Telephony.Sms.MESSAGE_TYPE_* so values stay compatible. */
object Box {
    const val INBOX = 1
    const val SENT = 2
    const val OUTBOX = 4
    const val FAILED = 5
    const val QUEUED = 6
}

object DeliveryStatus {
    const val NONE = -1
    const val PENDING = 32
    const val DELIVERED = 0
    const val FAILED = 64
}

/** Error codes Barq sets itself (the radio uses positive codes). */
object SendError {
    /** The user cancelled a bulk send before this message went out. */
    const val CANCELLED = -100
    /** Android refused the message before it reached the radio (bad number, no SIM…). */
    const val REJECTED = -101
}

@Immutable
@Serializable
data class Attachment(val mime: String, val path: String, val name: String? = null)

@Immutable
@Entity(
    tableName = "conversations",
    indices = [Index(value = ["deleted", "archived", "category", "pinned", "date"])]
)
data class ConversationEntity(
    @PrimaryKey val threadId: Long,
    /** Recipient addresses, comma separated. */
    val addresses: String,
    val displayName: String,
    val snippet: String,
    val date: Long,
    val unreadCount: Int = 0,
    val category: Category = Category.PERSONAL,
    /** True when a user rule decided the category — automatic classification won't override it. */
    val categoryLocked: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val deleted: Boolean = false,
    val deletedAt: Long = 0,
    val draft: String? = null,
    val subId: Int = -1,
    val lastFromMe: Boolean = false,
    val lastBox: Int = Box.INBOX,
    /** When the draft was last changed (0 = no draft). Drafts lift the conversation to the top. */
    @ColumnInfo(defaultValue = "0") val draftAt: Long = 0,
)

/** Time shown in the list and used for ordering: the newer of the last message and the draft. */
val ConversationEntity.sortTime: Long get() = if (draft != null) maxOf(date, draftAt) else date

@Immutable
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["threadId", "date"]),
        Index(value = ["providerId"], unique = true),
        Index(value = ["box", "scheduledAt"]),
        Index(value = ["otp"]),
        Index(value = ["batchId"]),
        Index(value = ["fromOtherApp", "date"]),
        Index(value = ["starred", "date"]),
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** _id in content://sms, null until written there. */
    val providerId: Long? = null,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long = 0,
    val box: Int,
    val read: Boolean = true,
    val deliveryStatus: Int = DeliveryStatus.NONE,
    val subId: Int = -1,
    val otp: String? = null,
    val category: Category = Category.PERSONAL,
    val isMms: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val subject: String? = null,
    val scheduledAt: Long = 0,
    val errorCode: Int = 0,
    val autoReply: Boolean = false,
    /** Bulk send this message belongs to (0 = a normal message). */
    @ColumnInfo(defaultValue = "0") val batchId: Long = 0,
    /** Normalised body for the full-text index (see [TextNorm]). */
    @ColumnInfo(defaultValue = "''") val searchText: String = TextNorm.indexText(body),
    /** Automatic retries already made after a temporary failure (no signal, Android's limit…). */
    @ColumnInfo(defaultValue = "0") val retryCount: Int = 0,
    /** A queued retry waits until this time (0 = ready now). */
    @ColumnInfo(defaultValue = "0") val retryAt: Long = 0,
    /** Sent by another app (a gateway, a bot…) and picked up from the system's SMS store. */
    @ColumnInfo(defaultValue = "0") val fromOtherApp: Boolean = false,
    /** My reaction to this message (an emoji), shown under the bubble. */
    val reaction: String? = null,
    /** The other person's reaction to this message, recognised from their reaction text. */
    val theirReaction: String? = null,
    /** This message is itself a reaction to message [reactionTo]: it is not shown as a bubble. */
    @ColumnInfo(defaultValue = "0") val reactionTo: Long = 0,
    /** Starred by the user: kept in "Starred messages". */
    @ColumnInfo(defaultValue = "0") val starred: Boolean = false,
)

/**
 * A single message the user deleted, kept in the recycle bin until restored or purged.
 * It no longer exists in `messages` nor in the system's SMS store.
 */
@Entity(tableName = "trashed_messages", indices = [Index(value = ["deletedAt"])])
data class TrashedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val box: Int,
    val subId: Int,
    val category: Category,
    val isMms: Boolean,
    val attachments: List<Attachment>,
    val subject: String?,
    val otp: String?,
    val starred: Boolean,
    val deletedAt: Long,
)

/** A binned message with the name of its conversation (null once the conversation is gone). */
data class TrashedMessage(
    @androidx.room.Embedded val message: TrashedMessageEntity,
    val displayName: String?,
)

/** A starred message with the name of its conversation. */
data class StarredMessage(
    @androidx.room.Embedded val message: MessageEntity,
    val displayName: String?,
)

/** Full-text index over [MessageEntity.searchText]; Room keeps it in sync with triggers. */
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFts(val searchText: String)

/** Kept outside the entity so Room never tries to map it to a column. */
val MessageEntity.isIncoming: Boolean get() = box == Box.INBOX

@Entity(tableName = "blocked_numbers")
data class BlockedNumberEntity(@PrimaryKey val number: String, val addedAt: Long = System.currentTimeMillis())

@Entity(tableName = "blocked_keywords")
data class BlockedKeywordEntity(@PrimaryKey val keyword: String, val addedAt: Long = System.currentTimeMillis())

/** "Messages from this sender always go to this category". */
@Entity(tableName = "category_rules", indices = [Index(value = ["sender"], unique = true)])
data class CategoryRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val category: Category,
)

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val position: Int = 0,
)

/** A reply the user wrote to an incoming text: the engine's memory (words only, never the full text). */
@Entity(
    tableName = "reply_memory",
    indices = [Index(value = ["incoming", "reply"], unique = true), Index(value = ["lastUsed"])],
)
data class ReplyMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The incoming text reduced to its words (see ReplyText.key). */
    val incoming: String,
    val reply: String,
    val uses: Int,
    val lastUsed: Long,
)

/** What the learner reads from the history. */
data class LearnRow(
    val id: Long,
    val threadId: Long,
    val box: Int,
    val body: String,
    val date: Long,
    val autoReply: Boolean,
    val category: Category,
    val address: String,
)

data class ThreadNames(val threadId: Long, val displayName: String, val addresses: String)

/** Remembers when we last auto-replied to a sender, to avoid reply loops and costs. */
/** Unread totals for the inbox's "Unread" view. */
data class UnreadSummary(val conversations: Int = 0, val messages: Int = 0)

@Entity(tableName = "auto_reply_log")
data class AutoReplyLogEntity(@PrimaryKey val address: String, val lastAt: Long)

/** A saved list of numbers to send to in one tap. */
@Entity(tableName = "send_groups")
data class SendGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "send_group_members", primaryKeys = ["groupId", "address"])
data class SendGroupMemberEntity(
    val groupId: Long,
    val address: String,
    val name: String? = null,
)

data class SendGroupWithCount(val id: Long, val name: String, val createdAt: Long, val members: Int)

/** Progress of one bulk send (or of what another app sent). */
data class BatchCounts(
    val total: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val pending: Int = 0,
    val delivered: Int = 0,
    /** Waiting for an automatic retry (counted in [pending] too). */
    val retrying: Int = 0,
)

/** Outside the class so Room only maps the four counted columns. */
val BatchCounts.done: Int get() = sent + failed
val BatchCounts.finished: Boolean get() = total > 0 && pending == 0

/** A message found by search, with its conversation name. */
data class SearchHit(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val box: Int,
    val displayName: String,
    val addresses: String,
    val category: Category,
)

/** One recipient of a bulk send, for the details screen. */
data class BatchRow(
    val id: Long,
    val threadId: Long,
    val address: String,
    val box: Int,
    val errorCode: Int,
    val deliveryStatus: Int,
    val retryCount: Int,
    val retryAt: Long,
    val date: Long,
    val displayName: String?,
)
