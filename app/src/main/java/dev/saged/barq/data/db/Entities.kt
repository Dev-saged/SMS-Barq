package dev.saged.barq.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
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

@Serializable
data class Attachment(val mime: String, val path: String, val name: String? = null)

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
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["threadId", "date"]),
        Index(value = ["providerId"], unique = true),
        Index(value = ["box", "scheduledAt"]),
        Index(value = ["otp"]),
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
)

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

/** Smart auto-reply: when an incoming text contains any keyword, answer with [reply]. */
@Entity(tableName = "auto_reply_rules")
data class AutoReplyRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Keywords separated by | or comma. */
    val keywords: String,
    val reply: String,
    val enabled: Boolean = true,
)

/** Remembers when we last auto-replied to a sender, to avoid reply loops and costs. */
@Entity(tableName = "auto_reply_log")
data class AutoReplyLogEntity(@PrimaryKey val address: String, val lastAt: Long)

data class CategoryCount(val category: Category, val count: Int)
