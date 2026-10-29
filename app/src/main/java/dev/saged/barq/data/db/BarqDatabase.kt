package dev.saged.barq.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun attachmentsToString(list: List<Attachment>): String =
        if (list.isEmpty()) "" else json.encodeToString(list)

    @TypeConverter
    fun stringToAttachments(value: String?): List<Attachment> =
        if (value.isNullOrEmpty()) emptyList() else runCatching {
            json.decodeFromString<List<Attachment>>(value)
        }.getOrDefault(emptyList())
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        BlockedNumberEntity::class,
        BlockedKeywordEntity::class,
        CategoryRuleEntity::class,
        TemplateEntity::class,
        AutoReplyRuleEntity::class,
        AutoReplyLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class BarqDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun blocks(): BlockDao
    abstract fun rules(): RuleDao
    abstract fun templates(): TemplateDao
    abstract fun autoReply(): AutoReplyDao
}
