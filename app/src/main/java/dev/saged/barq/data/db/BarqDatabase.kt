package dev.saged.barq.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.saged.barq.domain.TextNorm
import kotlinx.serialization.decodeFromString
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
        MessageFts::class,
        BlockedNumberEntity::class,
        BlockedKeywordEntity::class,
        CategoryRuleEntity::class,
        TemplateEntity::class,
        ReplyMemoryEntity::class,
        TrashedMessageEntity::class,
        AutoReplyLogEntity::class,
        SendGroupEntity::class,
        SendGroupMemberEntity::class,
    ],
    version = 6,
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
    abstract fun replies(): ReplyDao
    abstract fun groups(): GroupDao
    abstract fun trash(): TrashDao

    companion object {
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(Migration1To2, Migration2To3, Migration3To4, Migration4To5, Migration5To6)
    }
}

/**
 * Version 1 → 2 (Barq 1.2.0). Nothing is lost:
 *  - conversations.draftAt  : drafts lift a conversation to the top
 *  - messages.batchId       : progress of bulk sends
 *  - messages.searchText    : normalised text, filled here for every existing message
 *  - messages_fts           : full-text index (+ the triggers Room uses to keep it in sync)
 *  - send_groups / members  : saved recipient groups
 */
object Migration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `draftAt` INTEGER NOT NULL DEFAULT 0")
        // Existing drafts keep their place: their time is the conversation's last message time.
        db.execSQL("UPDATE `conversations` SET `draftAt` = `date` WHERE `draft` IS NOT NULL")

        db.execSQL("ALTER TABLE `messages` ADD COLUMN `batchId` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `searchText` TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_batchId` ON `messages` (`batchId`)")
        // Fill the search text before the index exists, so no trigger fires on half-built data.
        // Done in Kotlin so old and new messages are normalised by exactly the same code.
        val update = db.compileStatement("UPDATE `messages` SET `searchText` = ? WHERE `id` = ?")
        db.query("SELECT `id`, `body` FROM `messages`").use { c ->
            while (c.moveToNext()) {
                update.bindString(1, TextNorm.indexText(c.getString(1).orEmpty()))
                update.bindLong(2, c.getLong(0))
                update.executeUpdateDelete()
                update.clearBindings()
            }
        }
        update.close()

        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(`searchText` TEXT NOT NULL, content=`messages`)"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_UPDATE BEFORE UPDATE ON `messages` " +
                "BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_DELETE BEFORE DELETE ON `messages` " +
                "BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_UPDATE AFTER UPDATE ON `messages` " +
                "BEGIN INSERT INTO `messages_fts`(`docid`, `searchText`) VALUES (NEW.`rowid`, NEW.`searchText`); END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_INSERT AFTER INSERT ON `messages` " +
                "BEGIN INSERT INTO `messages_fts`(`docid`, `searchText`) VALUES (NEW.`rowid`, NEW.`searchText`); END"
        )
        db.execSQL("INSERT INTO `messages_fts`(`messages_fts`) VALUES('rebuild')")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `send_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `send_group_members` (`groupId` INTEGER NOT NULL, `address` TEXT NOT NULL, " +
                "`name` TEXT, PRIMARY KEY(`groupId`, `address`))"
        )
    }
}

/**
 * Version 2 → 3 (Barq 1.3.0). Only new columns with defaults, nothing is rewritten:
 *  - retryCount / retryAt : automatic retries after a temporary failure
 *  - fromOtherApp         : messages another app sent (followed live until the result is known)
 *  - reaction / theirReaction / reactionTo : emoji reactions
 */
object Migration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `retryAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `fromOtherApp` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `reaction` TEXT")
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `theirReaction` TEXT")
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `reactionTo` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_messages_fromOtherApp_date` ON `messages` (`fromOtherApp`, `date`)"
        )
    }
}

/** Version 3 → 4 (Barq 1.5.0): starred messages. Nothing else changes, nothing is lost. */
object Migration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `starred` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_starred_date` ON `messages` (`starred`, `date`)")
    }
}

/**
 * Version 4 → 5 (Barq 1.9.0): the reply engine's memory replaces the smart-reply rules.
 *  - reply_memory     : replies the user wrote, keyed by the words of the text they answered
 *  - auto_reply_rules : dropped (keyword rules are replaced by the learned replies)
 */
object Migration4To5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `auto_reply_rules`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reply_memory` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`incoming` TEXT NOT NULL, `reply` TEXT NOT NULL, `uses` INTEGER NOT NULL, `lastUsed` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_reply_memory_incoming_reply` ON `reply_memory` (`incoming`, `reply`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reply_memory_lastUsed` ON `reply_memory` (`lastUsed`)")
    }
}

/** Version 5 → 6 (Barq 1.9.3): single deleted messages go to the recycle bin instead of vanishing. */
object Migration5To6 : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trashed_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`threadId` INTEGER NOT NULL, `address` TEXT NOT NULL, `body` TEXT NOT NULL, `date` INTEGER NOT NULL, " +
                "`dateSent` INTEGER NOT NULL, `box` INTEGER NOT NULL, `subId` INTEGER NOT NULL, `category` TEXT NOT NULL, " +
                "`isMms` INTEGER NOT NULL, `attachments` TEXT NOT NULL, `subject` TEXT, `otp` TEXT, " +
                "`starred` INTEGER NOT NULL, `deletedAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trashed_messages_deletedAt` ON `trashed_messages` (`deletedAt`)")
    }
}
