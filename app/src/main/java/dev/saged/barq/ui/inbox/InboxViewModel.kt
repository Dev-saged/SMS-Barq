package dev.saged.barq.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.data.MessageRepository
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.ConversationDao
import dev.saged.barq.data.db.ConversationEntity
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.settings.SwipeAction
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val OTP_FRESH_MS = 15 * 60 * 1000L

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val conversations: ConversationDao,
    messages: MessageDao,
    private val repository: MessageRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val filter = MutableStateFlow<Category?>(null)
    val query = MutableStateFlow("")
    val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val dismissedOtp = MutableStateFlow(-1L)

    private val pagingConfig = PagingConfig(pageSize = 40, prefetchDistance = 20, enablePlaceholders = false)

    val conversationsPaged: Flow<PagingData<ConversationEntity>> =
        combine(filter, query.debounce(180).distinctUntilChanged()) { f, q -> f to q.trim() }
            .flatMapLatest { (f, q) -> Pager(pagingConfig) { conversations.inbox(f?.name, q) }.flow }
            .cachedIn(viewModelScope)

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val spamCount: StateFlow<Int> = conversations.spamCount().stateIn(viewModelScope, SharingStarted.Lazily, 0)

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

    fun dismissOtp(id: Long) {
        dismissedOtp.value = id
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

    fun pinSelected(pin: Boolean) = onSelected { repository.setPinned(it, pin) }
    fun muteSelected(mute: Boolean) = onSelected { repository.setMuted(it, mute) }
    fun archiveSelected() = onSelected { repository.setArchived(it, true) }
    fun readSelected() = onSelected { repository.markRead(it) }
    fun deleteSelected() = onSelected { repository.delete(it) }
    fun blockSelected() = onSelected { repository.block(it) }

    fun swipe(action: SwipeAction, conversation: ConversationEntity) {
        val ids = listOf(conversation.threadId)
        viewModelScope.launch {
            when (action) {
                SwipeAction.ARCHIVE -> repository.setArchived(ids, true)
                SwipeAction.DELETE -> repository.delete(ids)
                SwipeAction.READ -> repository.markRead(ids)
                SwipeAction.PIN -> repository.setPinned(ids, !conversation.pinned)
                SwipeAction.MUTE -> repository.setMuted(ids, !conversation.muted)
                SwipeAction.NONE -> Unit
            }
        }
    }

    fun undoArchive(threadId: Long) = viewModelScope.launch { repository.setArchived(listOf(threadId), false) }
    fun undoDelete(threadId: Long) = viewModelScope.launch { repository.restore(listOf(threadId)) }
}
