package dev.saged.barq.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    /**
     * Main inbox. [category] null = "all" (spam excluded). [query] empty = no filter; otherwise
     * conversations whose name or number match (message texts are searched by the FTS index).
     * [unreadOnly] keeps only conversations with unread messages.
     * A conversation with a newer draft is ordered by the draft time.
     */
    @Query(
        """
        SELECT * FROM conversations
        WHERE deleted = 0 AND archived = 0
          AND (date > 0 OR draft IS NOT NULL)
          AND ((:category IS NULL AND category != 'SPAM') OR category = :category)
          AND (:unreadOnly = 0 OR unreadCount > 0)
          AND (:query = '' OR displayName LIKE '%' || :query || '%'
               OR addresses LIKE '%' || :query || '%')
        ORDER BY pinned DESC,
                 (CASE WHEN draft IS NOT NULL AND draftAt > date THEN draftAt ELSE date END) DESC
        """
    )
    fun inbox(
        category: String?,
        query: String,
        unreadOnly: Boolean,
    ): PagingSource<Int, ConversationEntity>

    /** How many conversations and messages are unread (same scope as the inbox: no spam, no archive). */
    @Query(
        """
        SELECT COUNT(*) AS conversations, COALESCE(SUM(unreadCount), 0) AS messages FROM conversations
        WHERE deleted = 0 AND archived = 0 AND unreadCount > 0
          AND ((:category IS NULL AND category != 'SPAM') OR category = :category)
        """
    )
    fun unreadSummary(category: String?): Flow<UnreadSummary>

    @Query(
        """
        SELECT threadId FROM conversations
        WHERE deleted = 0 AND archived = 0 AND unreadCount > 0
          AND ((:category IS NULL AND category != 'SPAM') OR category = :category)
        """
    )
    suspend fun unreadIds(category: String?): List<Long>

    @Query("UPDATE conversations SET unreadCount = :count WHERE threadId = :threadId")
    suspend fun setUnreadCount(threadId: Long, count: Int)

    @Query(
        """SELECT * FROM conversations WHERE deleted = 0 AND archived = 1
           ORDER BY (CASE WHEN draft IS NOT NULL AND draftAt > date THEN draftAt ELSE date END) DESC"""
    )
    fun archived(): PagingSource<Int, ConversationEntity>

    @Query("SELECT * FROM conversations WHERE deleted = 1 ORDER BY deletedAt DESC")
    fun recycleBin(): PagingSource<Int, ConversationEntity>

    @Query("SELECT COUNT(*) FROM conversations WHERE deleted = 0 AND category = 'SPAM'")
    fun spamCount(): Flow<Int>

    @Query("SELECT * FROM conversations WHERE threadId = :threadId")
    suspend fun get(threadId: Long): ConversationEntity?

    @Query("SELECT threadId, displayName, addresses FROM conversations")
    suspend fun names(): List<ThreadNames>

    @Query("SELECT * FROM conversations WHERE threadId = :threadId")
    fun observe(threadId: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations")
    suspend fun all(): List<ConversationEntity>

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET pinned = :pinned WHERE threadId IN (:ids)")
    suspend fun setPinned(ids: List<Long>, pinned: Boolean)

    @Query("UPDATE conversations SET archived = :archived WHERE threadId IN (:ids)")
    suspend fun setArchived(ids: List<Long>, archived: Boolean)

    @Query("UPDATE conversations SET muted = :muted WHERE threadId IN (:ids)")
    suspend fun setMuted(ids: List<Long>, muted: Boolean)

    @Query("UPDATE conversations SET deleted = :deleted, deletedAt = :at WHERE threadId IN (:ids)")
    suspend fun setDeleted(ids: List<Long>, deleted: Boolean, at: Long)

    @Query("UPDATE conversations SET category = :category, categoryLocked = :locked WHERE threadId = :threadId")
    suspend fun setCategory(threadId: Long, category: Category, locked: Boolean)

    @Query("UPDATE conversations SET draft = :draft, draftAt = :draftAt WHERE threadId = :threadId")
    suspend fun setDraft(threadId: Long, draft: String?, draftAt: Long)

    /** Conversations opened from another app that never got a message or a draft. */
    @Query(
        """SELECT threadId FROM conversations WHERE date = 0 AND draft IS NULL
           AND threadId NOT IN (SELECT DISTINCT threadId FROM messages)"""
    )
    suspend fun emptyIds(): List<Long>

    @Query("UPDATE conversations SET subId = :subId WHERE threadId = :threadId")
    suspend fun setSubId(threadId: Long, subId: Int)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE threadId IN (:ids)")
    suspend fun clearUnread(ids: List<Long>)

    @Query("SELECT threadId FROM conversations WHERE deleted = 1 AND deletedAt < :before")
    suspend fun deletedBefore(before: Long): List<Long>

    @Query("SELECT threadId FROM conversations WHERE deleted = 1")
    suspend fun allDeletedIds(): List<Long>

    @Query("DELETE FROM conversations WHERE threadId IN (:ids)")
    suspend fun deleteForever(ids: List<Long>)

    @Query("UPDATE conversations SET displayName = :name WHERE threadId = :threadId")
    suspend fun setDisplayName(threadId: Long, name: String)
}

@Dao
interface MessageDao {

    /** A conversation, newest first. Reaction messages are shown on their target, not as bubbles. */
    @Query("SELECT * FROM messages WHERE threadId = :threadId AND reactionTo = 0 ORDER BY date DESC, id DESC")
    fun thread(threadId: Long): PagingSource<Int, MessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<MessageEntity>): List<Long>

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY date DESC, id DESC LIMIT 1")
    suspend fun latest(threadId: Long): MessageEntity?

    /** Latest message that should represent the conversation (future scheduled ones excluded). */
    @Query(
        """SELECT * FROM messages WHERE threadId = :threadId AND NOT (box = 6 AND scheduledAt > 0)
           AND reactionTo = 0
           ORDER BY date DESC, id DESC LIMIT 1"""
    )
    suspend fun latestVisible(threadId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE threadId = :threadId AND box = 1 ORDER BY date DESC LIMIT 1")
    suspend fun latestIncoming(threadId: Long): MessageEntity?

    /** The message the conversation ends with, kept up to date (drives the reply suggestions). */
    @Query(
        """SELECT * FROM messages WHERE threadId = :threadId AND NOT (box = 6 AND scheduledAt > 0)
           AND reactionTo = 0
           ORDER BY date DESC, id DESC LIMIT 1"""
    )
    fun observeLatestVisible(threadId: Long): Flow<MessageEntity?>

    /** The two newest messages (newest first): the one just sent and what it answers. */
    @Query(
        """SELECT * FROM messages WHERE threadId = :threadId AND NOT (box = 6 AND scheduledAt > 0)
           AND reactionTo = 0
           ORDER BY date DESC, id DESC LIMIT 2"""
    )
    suspend fun lastTurns(threadId: Long): List<MessageEntity>

    /** Plain texts of every conversation in time order, one page after the given position. */
    @Query(
        """SELECT id, threadId, box, body, date, autoReply, category, address FROM messages
           WHERE box IN (1, 2) AND isMms = 0 AND reactionTo = 0
           AND (threadId > :t OR (threadId = :t AND (date > :d OR (date = :d AND id > :i))))
           ORDER BY threadId ASC, date ASC, id ASC LIMIT :limit"""
    )
    suspend fun learnRows(t: Long, d: Long, i: Long, limit: Int): List<LearnRow>

    /** Position of a message in [thread] (newest first) — used to jump to a search result. */
    @Query(
        """SELECT COUNT(*) FROM messages WHERE threadId = :threadId AND reactionTo = 0
           AND (date > :date OR (date = :date AND id > :id))"""
    )
    suspend fun positionInThread(threadId: Long, date: Long, id: Long): Int

    @Query(
        """
        SELECT m.id AS id, m.threadId AS threadId, m.address AS address, m.body AS body, m.date AS date,
               m.box AS box, c.displayName AS displayName, c.addresses AS addresses, c.category AS category
        FROM messages_fts
        JOIN messages m ON m.id = messages_fts.rowid
        JOIN conversations c ON c.threadId = m.threadId
        WHERE messages_fts MATCH :match AND c.deleted = 0 AND NOT (m.box = 6 AND m.scheduledAt > 0)
        ORDER BY m.date DESC
        LIMIT :limit
        """
    )
    suspend fun search(match: String, limit: Int): List<SearchHit>

    /** Search inside one conversation (newest first), for the search bar of a thread. */
    @Query(
        """
        SELECT m.id AS id, m.date AS date FROM messages_fts
        JOIN messages m ON m.id = messages_fts.rowid
        WHERE messages_fts MATCH :match AND m.threadId = :threadId AND m.reactionTo = 0
          AND NOT (m.box = 6 AND m.scheduledAt > 0)
        ORDER BY m.date DESC, m.id DESC
        LIMIT 500
        """
    )
    suspend fun searchInThread(match: String, threadId: Long): List<ThreadHit>

    /** Recent messages of a conversation, to find the one a reaction text points at. */
    @Query("SELECT * FROM messages WHERE threadId = :threadId AND reactionTo = 0 ORDER BY date DESC, id DESC LIMIT :limit")
    suspend fun recentInThread(threadId: Long, limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET reaction = :emoji WHERE id = :id")
    suspend fun setReaction(id: Long, emoji: String?)

    @Query("UPDATE messages SET category = :category WHERE threadId = :threadId AND box = 1")
    suspend fun setIncomingCategory(threadId: Long, category: Category)

    @Query("UPDATE messages SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query(
        """SELECT m.*, c.displayName AS displayName FROM messages m
           LEFT JOIN conversations c ON c.threadId = m.threadId
           WHERE m.starred = 1 AND c.deleted = 0 ORDER BY m.date DESC"""
    )
    fun observeStarred(): kotlinx.coroutines.flow.Flow<List<StarredMessage>>

    @Query("UPDATE messages SET theirReaction = :emoji WHERE id = :id")
    suspend fun setTheirReaction(id: Long, emoji: String?)

    @Query("UPDATE messages SET reactionTo = :target WHERE id = :id")
    suspend fun setReactionTo(id: Long, target: Long)

    @Query("SELECT * FROM messages WHERE threadId = :threadId AND read = 0 AND box = 1 ORDER BY date ASC")
    suspend fun unread(threadId: Long): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE threadId = :threadId AND read = 0 AND box = 1")
    suspend fun unreadCount(threadId: Long): Int

    @Query("SELECT providerId FROM messages WHERE threadId IN (:ids) AND read = 0 AND providerId IS NOT NULL")
    suspend fun unreadProviderIds(ids: List<Long>): List<Long>

    @Query("UPDATE messages SET read = 1 WHERE threadId IN (:ids) AND read = 0")
    suspend fun markRead(ids: List<Long>)

    @Query("UPDATE messages SET read = 0 WHERE id = :id")
    suspend fun markUnread(id: Long)

    @Query("UPDATE messages SET box = :box, errorCode = :errorCode WHERE id = :id")
    suspend fun setBox(id: Long, box: Int, errorCode: Int = 0)

    @Query("UPDATE messages SET deliveryStatus = :status WHERE id = :id")
    suspend fun setDelivery(id: Long, status: Int)

    /**
     * Messages ready to go. A normal message never waits behind a bulk send: single messages come
     * first. [pausedBatch] (when not 0) is skipped while the user paused it; retries wait for
     * their time.
     */
    @Query(
        """SELECT * FROM messages WHERE box = 6 AND (scheduledAt = 0 OR scheduledAt <= :now)
           AND retryAt <= :now
           AND (:pausedBatch = 0 OR batchId != :pausedBatch)
           ORDER BY (CASE WHEN batchId = 0 THEN 0 ELSE 1 END) ASC, scheduledAt ASC, id ASC LIMIT :limit"""
    )
    suspend fun dueQueued(now: Long, limit: Int, pausedBatch: Long): List<MessageEntity>

    /** When the next automatic retry is due (null = none waiting). */
    @Query("SELECT MIN(retryAt) FROM messages WHERE box = 6 AND retryAt > :now")
    suspend fun nextRetryAt(now: Long): Long?

    /** A temporary failure: back in the queue, to try again at [at]. */
    @Query("UPDATE messages SET box = 6, retryCount = :count, retryAt = :at, errorCode = :code WHERE id = :id")
    suspend fun scheduleRetry(id: Long, count: Int, at: Long, code: Int)

    @Query(
        """SELECT COUNT(*) AS total,
                  COALESCE(SUM(CASE WHEN box = 2 THEN 1 ELSE 0 END), 0) AS sent,
                  COALESCE(SUM(CASE WHEN box = 5 THEN 1 ELSE 0 END), 0) AS failed,
                  COALESCE(SUM(CASE WHEN box IN (4, 6) THEN 1 ELSE 0 END), 0) AS pending,
                  COALESCE(SUM(CASE WHEN box = 2 AND deliveryStatus = 0 THEN 1 ELSE 0 END), 0) AS delivered,
                  COALESCE(SUM(CASE WHEN box = 6 AND retryAt > 0 THEN 1 ELSE 0 END), 0) AS retrying
           FROM messages WHERE batchId = :batchId"""
    )
    fun observeBatch(batchId: Long): Flow<BatchCounts>

    @Query(
        """SELECT COUNT(*) AS total,
                  COALESCE(SUM(CASE WHEN box = 2 THEN 1 ELSE 0 END), 0) AS sent,
                  COALESCE(SUM(CASE WHEN box = 5 THEN 1 ELSE 0 END), 0) AS failed,
                  COALESCE(SUM(CASE WHEN box IN (4, 6) THEN 1 ELSE 0 END), 0) AS pending,
                  COALESCE(SUM(CASE WHEN box = 2 AND deliveryStatus = 0 THEN 1 ELSE 0 END), 0) AS delivered,
                  COALESCE(SUM(CASE WHEN box = 6 AND retryAt > 0 THEN 1 ELSE 0 END), 0) AS retrying
           FROM messages WHERE batchId = :batchId"""
    )
    suspend fun batchCounts(batchId: Long): BatchCounts

    @Query("SELECT * FROM messages WHERE batchId = :batchId AND box = 5")
    suspend fun failedInBatch(batchId: Long): List<MessageEntity>

    /** Every recipient of a bulk send with its state, for the details screen. */
    @Query(
        """SELECT m.id AS id, m.threadId AS threadId, m.address AS address, m.box AS box,
                  m.errorCode AS errorCode, m.deliveryStatus AS deliveryStatus, m.retryCount AS retryCount,
                  m.retryAt AS retryAt, m.date AS date, c.displayName AS displayName
           FROM messages m LEFT JOIN conversations c ON c.threadId = m.threadId
           WHERE m.batchId = :batchId ORDER BY m.id ASC"""
    )
    fun observeBatchRows(batchId: Long): Flow<List<BatchRow>>

    /** What other apps (a gateway, a bot…) sent since [since]. */
    @Query(
        """SELECT COUNT(*) AS total,
                  COALESCE(SUM(CASE WHEN box = 2 THEN 1 ELSE 0 END), 0) AS sent,
                  COALESCE(SUM(CASE WHEN box = 5 THEN 1 ELSE 0 END), 0) AS failed,
                  COALESCE(SUM(CASE WHEN box IN (4, 6) THEN 1 ELSE 0 END), 0) AS pending,
                  COALESCE(SUM(CASE WHEN box = 2 AND deliveryStatus = 0 THEN 1 ELSE 0 END), 0) AS delivered,
                  COALESCE(SUM(CASE WHEN box = 6 AND retryAt > 0 THEN 1 ELSE 0 END), 0) AS retrying
           FROM messages WHERE fromOtherApp = 1 AND date >= :since"""
    )
    fun observeExternal(since: Long): Flow<BatchCounts>

    @Query(
        """SELECT m.id AS id, m.threadId AS threadId, m.address AS address, m.box AS box,
                  m.errorCode AS errorCode, m.deliveryStatus AS deliveryStatus, m.retryCount AS retryCount,
                  m.retryAt AS retryAt, m.date AS date, c.displayName AS displayName
           FROM messages m LEFT JOIN conversations c ON c.threadId = m.threadId
           WHERE m.fromOtherApp = 1 AND m.date >= :since ORDER BY m.date ASC, m.id ASC"""
    )
    fun observeExternalRows(since: Long): Flow<List<BatchRow>>

    @Query("SELECT * FROM messages WHERE fromOtherApp = 1 AND date >= :since AND box = 5")
    suspend fun failedExternal(since: Long): List<MessageEntity>

    /** كل الرسائل الفاشلة، الأحدث أولاً، لشاشة «الرسائل الفاشلة». */
    @Query(
        """SELECT m.id AS id, m.threadId AS threadId, m.address AS address, m.box AS box,
                  m.errorCode AS errorCode, m.deliveryStatus AS deliveryStatus, m.retryCount AS retryCount,
                  m.retryAt AS retryAt, m.date AS date, c.displayName AS displayName
           FROM messages m LEFT JOIN conversations c ON c.threadId = m.threadId
           WHERE m.box = 5 AND m.reactionTo = 0 ORDER BY m.date DESC, m.id DESC"""
    )
    fun observeFailedRows(): Flow<List<BatchRow>>

    @Query("SELECT COUNT(*) FROM messages WHERE box = 5 AND reactionTo = 0")
    fun failedCount(): Flow<Int>

    /** Other apps' messages still on their way: followed until the system reports the result. */
    @Query("SELECT providerId FROM messages WHERE fromOtherApp = 1 AND box IN (4, 6) AND providerId IS NOT NULL")
    suspend fun externalPendingProviderIds(): List<Long>

    /** Failed messages that are in the system store (some may have gone out after all). */
    @Query("SELECT providerId FROM messages WHERE box = 5 AND providerId IS NOT NULL")
    suspend fun failedProviderIds(): List<Long>

    @Query("SELECT * FROM messages WHERE providerId IN (:providerIds)")
    suspend fun byProviderIds(providerIds: List<Long>): List<MessageEntity>

    @Query("UPDATE messages SET box = :box, errorCode = :errorCode, deliveryStatus = :delivery WHERE id = :id")
    suspend fun applyProviderState(id: Long, box: Int, errorCode: Int, delivery: Int)

    @Query("SELECT DISTINCT threadId FROM messages WHERE batchId = :batchId")
    suspend fun threadsInBatch(batchId: Long): List<Long>

    /** Cancelling a bulk send: what has not gone out yet is marked failed (can be resent later). */
    @Query("UPDATE messages SET box = 5, errorCode = :code WHERE batchId = :batchId AND box = 6")
    suspend fun cancelBatch(batchId: Long, code: Int): Int

    @Query("SELECT address || '|' || date FROM messages")
    suspend fun allKeys(): List<String>

    @Query("SELECT date, box, subId, body FROM messages WHERE date >= :from AND isMms = 0 AND box IN (1, 2, 5)")
    suspend fun statRows(from: Long): List<StatRow>

    @Query(
        """SELECT m.threadId AS threadId, c.displayName AS name, c.addresses AS addresses, COUNT(*) AS count
           FROM messages m JOIN conversations c ON c.threadId = m.threadId
           WHERE m.date >= :from AND c.deleted = 0 AND m.box IN (1, 2)
           GROUP BY m.threadId ORDER BY count DESC LIMIT :limit"""
    )
    suspend fun topThreads(from: Long, limit: Int): List<TopThread>

    @Query("SELECT * FROM messages WHERE box = 6 AND scheduledAt > :now")
    suspend fun futureScheduled(now: Long): List<MessageEntity>

    /** Messages stuck in OUTBOX after a crash/reboot. */
    @Query("SELECT * FROM messages WHERE box = 4 AND fromOtherApp = 0 AND date < :before")
    suspend fun staleOutbox(before: Long): List<MessageEntity>

    // Codes from services only: a number a friend sends keeps its copy button on the bubble but
    // does not take over the inbox card or the widget.
    @Query("SELECT * FROM messages WHERE otp IS NOT NULL AND category = 'OTP' ORDER BY date DESC LIMIT 1")
    fun observeLatestOtp(): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE otp IS NOT NULL AND category = 'OTP' ORDER BY date DESC LIMIT 1")
    suspend fun latestOtp(): MessageEntity?

    @Query("SELECT MAX(providerId) FROM messages WHERE isMms = 0")
    suspend fun maxProviderId(): Long?

    @Query("SELECT providerId FROM messages WHERE threadId IN (:ids) AND providerId IS NOT NULL")
    suspend fun providerIds(ids: List<Long>): List<Long>

    @Query("SELECT * FROM messages WHERE threadId IN (:ids) AND isMms = 1")
    suspend fun mmsIn(ids: List<Long>): List<MessageEntity>

    @Query("DELETE FROM messages WHERE threadId IN (:ids)")
    suspend fun deleteThreads(ids: List<Long>)

    @Query("UPDATE messages SET threadId = :to WHERE threadId = :from")
    suspend fun moveThread(from: Long, to: Long)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM messages WHERE isMms = 0 ORDER BY id ASC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE isMms = 0 AND box != 6")
    suspend fun countExportable(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE address = :address AND date = :date AND body = :body")
    suspend fun countSame(address: String, date: Long, body: String): Int

    @Query("SELECT DISTINCT threadId FROM messages")
    suspend fun threadIds(): List<Long>
}

data class ThreadHit(val id: Long, val date: Long)

data class StatRow(val date: Long, val box: Int, val subId: Int, val body: String)

data class TopThread(val threadId: Long, val name: String, val addresses: String, val count: Int)

@Dao
interface TrashDao {
    @Insert
    suspend fun insertAll(rows: List<TrashedMessageEntity>)

    @Query(
        """SELECT t.*, c.displayName AS displayName
           FROM trashed_messages t LEFT JOIN conversations c ON c.threadId = t.threadId
           ORDER BY t.deletedAt DESC, t.id DESC"""
    )
    fun observe(): Flow<List<TrashedMessage>>

    @Query("SELECT * FROM trashed_messages WHERE id IN (:ids)")
    suspend fun get(ids: List<Long>): List<TrashedMessageEntity>

    @Query("SELECT id FROM trashed_messages")
    suspend fun allIds(): List<Long>

    @Query("SELECT id FROM trashed_messages WHERE deletedAt < :before")
    suspend fun idsBefore(before: Long): List<Long>

    @Query("DELETE FROM trashed_messages WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Dao
interface GroupDao {
    @Query(
        """SELECT g.id AS id, g.name AS name, g.createdAt AS createdAt,
                  (SELECT COUNT(*) FROM send_group_members m WHERE m.groupId = g.id) AS members
           FROM send_groups g ORDER BY g.name COLLATE NOCASE ASC"""
    )
    fun observe(): Flow<List<SendGroupWithCount>>

    @Query("SELECT * FROM send_groups ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<SendGroupEntity>

    @Query("SELECT * FROM send_groups WHERE id = :id")
    fun observeGroup(id: Long): Flow<SendGroupEntity?>

    @Insert
    suspend fun insert(group: SendGroupEntity): Long

    @Query("UPDATE send_groups SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM send_groups WHERE id = :id")
    suspend fun deleteGroup(id: Long)

    @Query("SELECT * FROM send_group_members WHERE groupId = :groupId ORDER BY name COLLATE NOCASE ASC, address ASC")
    fun observeMembers(groupId: Long): Flow<List<SendGroupMemberEntity>>

    @Query("SELECT * FROM send_group_members WHERE groupId = :groupId")
    suspend fun members(groupId: Long): List<SendGroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMembers(members: List<SendGroupMemberEntity>)

    @Query("DELETE FROM send_group_members WHERE groupId = :groupId AND address = :address")
    suspend fun removeMember(groupId: Long, address: String)

    @Query("DELETE FROM send_group_members WHERE groupId = :groupId")
    suspend fun clearMembers(groupId: Long)
}

@Dao
interface BlockDao {
    @Query("SELECT * FROM blocked_numbers ORDER BY addedAt DESC")
    fun numbers(): Flow<List<BlockedNumberEntity>>

    @Query("SELECT * FROM blocked_numbers")
    suspend fun numbersOnce(): List<BlockedNumberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addNumber(n: BlockedNumberEntity)

    @Delete
    suspend fun removeNumber(n: BlockedNumberEntity)

    @Query("SELECT * FROM blocked_keywords ORDER BY addedAt DESC")
    fun keywords(): Flow<List<BlockedKeywordEntity>>

    @Query("SELECT * FROM blocked_keywords")
    suspend fun keywordsOnce(): List<BlockedKeywordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addKeyword(k: BlockedKeywordEntity)

    @Delete
    suspend fun removeKeyword(k: BlockedKeywordEntity)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM category_rules ORDER BY id DESC")
    fun observe(): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rules")
    suspend fun all(): List<CategoryRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: CategoryRuleEntity)

    @Query("DELETE FROM category_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY position ASC, id ASC")
    fun observe(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates ORDER BY position ASC, id ASC")
    suspend fun all(): List<TemplateEntity>

    @Upsert
    suspend fun upsert(template: TemplateEntity)

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM templates")
    suspend fun count(): Int
}

@Dao
interface AutoReplyDao {
    @Query("SELECT * FROM auto_reply_log WHERE address = :address")
    suspend fun lastReply(address: String): AutoReplyLogEntity?

    @Upsert
    suspend fun logReply(log: AutoReplyLogEntity)
}

@Dao
interface ReplyDao {
    @Query("SELECT * FROM reply_memory")
    suspend fun all(): List<ReplyMemoryEntity>

    @Query("SELECT COUNT(*) FROM reply_memory")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM reply_memory")
    suspend fun count(): Int

    @Query("UPDATE reply_memory SET uses = uses + 1, lastUsed = MAX(lastUsed, :at) WHERE incoming = :incoming AND reply = :reply")
    suspend fun bump(incoming: String, reply: String, at: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ReplyMemoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<ReplyMemoryEntity>)

    @Query("DELETE FROM reply_memory")
    suspend fun clear()

    /** Forgets the [n] least used, oldest entries. */
    @Query("DELETE FROM reply_memory WHERE id IN (SELECT id FROM reply_memory ORDER BY uses ASC, lastUsed ASC LIMIT :n)")
    suspend fun prune(n: Int)
}
