package dev.saged.barq.ui.thread

import android.telephony.SmsMessage
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.data.MessageRepository
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.ConversationDao
import dev.saged.barq.data.db.ConversationEntity
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.db.TemplateDao
import dev.saged.barq.data.db.TemplateEntity
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.sim.SimInfo
import dev.saged.barq.data.sim.SimRepository
import dev.saged.barq.notifications.Notifier
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** SMS length info for the counter: parts used and characters left in the current part. */
data class SmsCount(val parts: Int, val remaining: Int)

@OptIn(FlowPreview::class)
@HiltViewModel
class ThreadViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val conversations: ConversationDao,
    messageDao: MessageDao,
    templateDao: TemplateDao,
    private val repository: MessageRepository,
    private val notifier: Notifier,
    settingsRepository: SettingsRepository,
    simRepository: SimRepository,
) : ViewModel() {

    val threadId: Long = savedState.get<Long>("threadId") ?: -1L

    val conversation: StateFlow<ConversationEntity?> =
        conversations.observe(threadId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val messages = Pager(PagingConfig(pageSize = 50, prefetchDistance = 30, enablePlaceholders = false)) {
        messageDao.thread(threadId)
    }.flow.cachedIn(viewModelScope)

    val templates: StateFlow<List<TemplateEntity>> =
        templateDao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val sims: List<SimInfo> = simRepository.activeSims()
    val subId = MutableStateFlow(-1)
    val text = MutableStateFlow("")
    val count = MutableStateFlow(SmsCount(0, 0))

    init {
        viewModelScope.launch {
            val conv = conversations.get(threadId)
            text.value = conv?.draft.orEmpty()
            updateCount(text.value)
            val s = settingsRepository.current()
            subId.value = when {
                conv != null && conv.subId >= 0 && sims.any { it.subId == conv.subId } -> conv.subId
                s.defaultSubId >= 0 && sims.any { it.subId == s.defaultSubId } -> s.defaultSubId
                else -> sims.firstOrNull()?.subId ?: -1
            }
            markRead()
        }
        // Drafts are saved quietly while typing.
        viewModelScope.launch {
            text.drop(1).debounce(500).collect { repository.setDraft(threadId, it) }
        }
    }

    fun markRead() {
        viewModelScope.launch {
            repository.markRead(listOf(threadId))
            notifier.cancel(threadId)
        }
    }

    fun onTextChange(value: String) {
        text.value = value
        updateCount(value)
    }

    private fun updateCount(value: String) {
        count.value = if (value.isEmpty()) SmsCount(0, 0) else runCatching {
            val r = SmsMessage.calculateLength(value, false)
            SmsCount(r[0], r[2])
        }.getOrDefault(SmsCount(1, 0))
    }

    fun insertTemplate(template: String) {
        val current = text.value
        onTextChange(if (current.isBlank()) template else "$current $template")
    }

    fun cycleSim() {
        if (sims.size < 2) return
        val i = sims.indexOfFirst { it.subId == subId.value }
        subId.value = sims[(i + 1).mod(sims.size)].subId
        viewModelScope.launch { repository.setSubId(threadId, subId.value) }
    }

    fun send(scheduledAt: Long = 0) {
        val body = text.value.trim()
        if (body.isEmpty()) return
        onTextChange("")
        viewModelScope.launch {
            repository.queueToThread(threadId, body, subId.value, scheduledAt)
            repository.setDraft(threadId, null)
        }
    }

    fun resend(id: Long) = viewModelScope.launch { repository.resend(id) }
    fun deleteMessage(id: Long) = viewModelScope.launch { repository.deleteMessage(id) }
    fun cancelScheduled(id: Long) = viewModelScope.launch { repository.cancelScheduled(id) }
    fun setCategory(category: Category) = viewModelScope.launch { repository.setCategoryForSender(threadId, category) }

    fun togglePin() = viewModelScope.launch {
        conversation.value?.let { repository.setPinned(listOf(threadId), !it.pinned) }
    }

    fun toggleMute() = viewModelScope.launch {
        conversation.value?.let { repository.setMuted(listOf(threadId), !it.muted) }
    }

    fun archive(done: () -> Unit) = viewModelScope.launch {
        repository.setArchived(listOf(threadId), true)
        done()
    }

    fun delete(done: () -> Unit) = viewModelScope.launch {
        repository.delete(listOf(threadId))
        done()
    }

    fun block(done: () -> Unit) = viewModelScope.launch {
        repository.block(listOf(threadId))
        done()
    }

    /** Waits for the conversation row (used when a thread is opened before it exists). */
    suspend fun awaitConversation(): ConversationEntity? = conversations.observe(threadId).first()
}
