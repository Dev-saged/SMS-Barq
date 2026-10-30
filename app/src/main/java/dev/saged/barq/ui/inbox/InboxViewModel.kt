package dev.saged.barq.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.data.ConversationRepository
import dev.saged.barq.data.db.BatchCounts
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.ConversationDao
import dev.saged.barq.data.db.ConversationEntity
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.db.SearchHit
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.settings.SwipeAction
import dev.saged.barq.domain.TextNorm
import dev.saged.barq.notifications.Notifier
import dev.saged.barq.sms.SendEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val OTP_FRESH_MS = 15 * 60 * 1000L
private const val EXTERNAL_WINDOW_MS = 12 * 60 * 60 * 1000L

/** The bulk-send card on top of the inbox. */
data class BulkUi(
    val batchId: Long,
    val counts: BatchCounts,
    val preparing: Boolean,
    val paused: Boolean,
    /** Pacing under Android's per-minute limit. */
    val waitingLimit: Boolean = false,
    /** The radio stopped answering (Android may be asking the user something). */
    val stalled: Boolean = false,
)

/** What other apps (a gateway, a bot…) sent recently, shown as a card until closed. */
data class ExternalUi(val since: Long, val counts: BatchCounts)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    conversationDao: ConversationDao,
    private val messages: MessageDao,
    private val conversations: ConversationRepository,
    private val engine: SendEngine,
    private val notifier: Notifier,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val filter = MutableStateFlow<Category?>(null)
    val query = MutableStateFlow("")
    val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val dismissedOtp = MutableStateFlow(-1L)

    private val pagingConfig = PagingConfig(pageSize = 40, prefetchDistance = 20, enablePlaceholders = false)

    private val debouncedQuery = query.debounce(180).map { it.trim() }.distinctUntilChanged()

    val conversationsPaged: Flow<PagingData<ConversationEntity>> =
        combine(filter, debouncedQuery) { f, q -> f to q }
            .flatMapLatest { (f, q) -> Pager(pagingConfig) { conversationDao.inbox(f?.name, q) }.flow }
            .cachedIn(viewModelScope)

    /** Messages matching the search, from the full-text index (instant even with 100k messages). */
    val messageHits: StateFlow<List<SearchHit>> = debouncedQuery
        .mapLatest { q ->
            val match = TextNorm.ftsQuery(q) ?: return@mapLatest emptyList<SearchHit>()
            runCatching { messages.search(match, 150) }.getOrDefault(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val spamCount: StateFlow<Int> = conversationDao.spamCount().stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    /** Latest verification code, shown on top of the inbox for 15 minutes. */
    val freshOtp: StateFlow<MessageEntity?> =
        combine(messages.observeLatestOtp(), dismissedOtp, ticker) { m, dismissed, now ->
            m?.takeIf { it.id != dismissed && now - it.date < OTP_FRESH_MS }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Progress of the last bulk send, until the user closes the card. */
    val bulk: StateFlow<BulkUi?> = settings
        .map { it.lastBatchId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == 0L) flowOf(null)
            else combine(
                engine.observeBatch(id), engine.preparing, engine.paused, engine.waitingLimitUntil, engine.stalled,
            ) { counts, preparing, paused, waitUntil, stalled ->
                BulkUi(id, counts, preparing, paused == id, waitUntil > 0L, stalled)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Sends by other apps since the card was last closed (at most the last 12 hours). */
    val external: StateFlow<ExternalUi?> = settings
        .map { it.externalSeenAt }
        .distinctUntilChanged()
        .flatMapLatest { seen ->
            val since = maxOf(seen + 1, System.currentTimeMillis() - EXTERNAL_WINDOW_MS)
            engine.observeExternal(since).map { c -> if (c.total == 0) null else ExternalUi(since, c) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismissExternal() = viewModelScope.launch { settingsRepository.setExternalSeenAt(System.currentTimeMillis()) }
    fun resendExternal(since: Long) = viewModelScope.launch { engine.resendFailedExternal(since) }

    fun dismissOtp(id: Long) {
        dismissedOtp.value = id
    }

    /** The code was copied from the card: its notification goes away and the message is read. */
    fun onOtpCopied(message: MessageEntity) {
        viewModelScope.launch {
            conversations.markRead(listOf(message.threadId))
            notifier.cancel(message.threadId)
        }
    }

    fun toggleSelection(threadId: Long) {
        selection.value = selection.value.let { if (threadId in it) it - threadId else it + threadId }
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    private fun onSelected(block: suspend (List<Long>) -> Unit) {
        val ids = selection.value.toList()
        clearSelection()
        if (ids.isNotEmpty()) viewModelScope.launch { block(ids) }
    }

    fun pinSelected(pin: Boolean) = onSelected { conversations.setPinned(it, pin) }
    fun muteSelected(mute: Boolean) = onSelected { conversations.setMuted(it, mute) }
    fun archiveSelected() = onSelected { conversations.setArchived(it, true) }
    fun readSelected() = onSelected { conversations.markRead(it) }
    fun deleteSelected() = onSelected { conversations.delete(it) }
    fun blockSelected() = onSelected { conversations.block(it) }

    fun swipe(action: SwipeAction, conversation: ConversationEntity) {
        val ids = listOf(conversation.threadId)
        viewModelScope.launch {
            when (action) {
                SwipeAction.ARCHIVE -> conversations.setArchived(ids, true)
                SwipeAction.DELETE -> conversations.delete(ids)
                SwipeAction.READ -> conversations.markRead(ids)
                SwipeAction.PIN -> conversations.setPinned(ids, !conversation.pinned)
                SwipeAction.MUTE -> conversations.setMuted(ids, !conversation.muted)
                SwipeAction.NONE -> Unit
            }
        }
    }

    fun undoArchive(threadId: Long) = viewModelScope.launch { conversations.setArchived(listOf(threadId), false) }
    fun undoDelete(threadId: Long) = viewModelScope.launch { conversations.restore(listOf(threadId)) }

    // Bulk card
    fun pauseBulk(id: Long) = engine.pauseBatch(id)
    fun resumeBulk() = engine.resumeBatch()
    fun cancelBulk(id: Long) = viewModelScope.launch { engine.cancelBatch(id) }
    fun resendFailed(id: Long) = viewModelScope.launch { engine.resendFailed(id) }
    fun dismissBulk() = viewModelScope.launch { engine.dismissBatch() }
}
