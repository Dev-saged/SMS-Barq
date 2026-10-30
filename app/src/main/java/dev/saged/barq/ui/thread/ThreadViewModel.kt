package dev.saged.barq.ui.thread

import android.telephony.SmsMessage
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.data.ConversationRepository
import dev.saged.barq.data.ReactionRepository
import dev.saged.barq.data.db.ThreadHit
import dev.saged.barq.domain.TextNorm
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.ConversationDao
import dev.saged.barq.data.db.ConversationEntity
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.db.TemplateDao
import dev.saged.barq.data.db.TemplateEntity
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.sim.SimInfo
import dev.saged.barq.data.sim.SimRepository
import dev.saged.barq.di.AppScope
import dev.saged.barq.notifications.Notifier
import dev.saged.barq.sms.SendEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** SMS length info for the counter: parts used and characters left in the current part. */
data class SmsCount(val parts: Int, val remaining: Int)

@OptIn(FlowPreview::class)
@HiltViewModel
class ThreadViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    templateDao: TemplateDao,
    private val conversations: ConversationRepository,
    private val engine: SendEngine,
    private val notifier: Notifier,
    private val settingsRepository: SettingsRepository,
    private val simRepository: SimRepository,
    private val reactions: ReactionRepository,
    @AppScope private val appScope: CoroutineScope,
) : ViewModel() {

    val threadId: Long = savedState.get<Long>("threadId") ?: -1L

    /** A message to jump to (opened from search), or -1. */
    val focusId: Long = savedState.get<Long>("focus") ?: -1L

    /** Position of [focusId] in the list (newest first), -1 when there is none. */
    val focusIndex = MutableStateFlow(-1)

    val conversation: StateFlow<ConversationEntity?> =
        conversationDao.observe(threadId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Messages, newest first. When jumping to a search result the list starts around it and uses
     * placeholders, so it can scroll straight to a message thousands of lines back.
     */
    val messages: Flow<PagingData<MessageEntity>> = flow {
        var initialKey: Int? = null
        if (focusId > 0) {
            val target = messageDao.get(focusId)
            if (target != null && target.threadId == threadId) {
                val position = messageDao.positionInThread(threadId, target.date, target.id)
                focusIndex.value = position
                initialKey = (position - 20).coerceAtLeast(0)
            }
        }
        // Placeholders let the list jump straight to any message (search results), however far back.
        val config = PagingConfig(
            pageSize = 50, prefetchDistance = 30, initialLoadSize = 100,
            enablePlaceholders = true, jumpThreshold = 150,
        )
        emitAll(Pager(config, initialKey = initialKey) { messageDao.thread(threadId) }.flow)
    }.cachedIn(viewModelScope)

    val templates: StateFlow<List<TemplateEntity>> =
        templateDao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val sims = MutableStateFlow<List<SimInfo>>(emptyList())
    val subId = MutableStateFlow(-1)
    val text = MutableStateFlow("")
    val count = MutableStateFlow(SmsCount(0, 0))

    init {
        viewModelScope.launch {
            val conv = conversationDao.get(threadId)
            text.value = conv?.draft.orEmpty()
            updateCount(text.value)
            refreshSimsNow()
            markRead()
        }
        // Drafts are saved quietly while typing.
        viewModelScope.launch {
            text.drop(1).debounce(500).collect { conversations.setDraft(threadId, it) }
        }
    }

    private suspend fun refreshSimsNow() {
        val list = simRepository.activeSims()
        sims.value = list
        if (list.none { it.subId == subId.value }) {
            val conv = conversation.value ?: conversationDao.get(threadId)
            val def = settingsRepository.current().defaultSubId
            subId.value = when {
                conv != null && conv.subId >= 0 && list.any { it.subId == conv.subId } -> conv.subId
                def >= 0 && list.any { it.subId == def } -> def
                else -> list.firstOrNull()?.subId ?: -1
            }
        }
    }

    /** Re-read on every resume, so a SIM appears right after the phone permission is granted. */
    fun refreshSims() {
        viewModelScope.launch { refreshSimsNow() }
    }

    fun markRead() {
        viewModelScope.launch {
            conversations.markRead(listOf(threadId))
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

    /** The SIM chosen on the two-SIM switch; remembered for this conversation. */
    fun pickSim(id: Int) {
        if (id == subId.value) return
        subId.value = id
        viewModelScope.launch { conversations.setSubId(threadId, id) }
    }

    fun cycleSim() {
        val list = sims.value
        if (list.size < 2) return
        val i = list.indexOfFirst { it.subId == subId.value }
        subId.value = list[(i + 1).mod(list.size)].subId
        viewModelScope.launch { conversations.setSubId(threadId, subId.value) }
    }

    fun send(scheduledAt: Long = 0) {
        val body = text.value.trim()
        if (body.isEmpty()) return
        onTextChange("")
        viewModelScope.launch {
            engine.sendToThread(threadId, body, subId.value.takeIf { it >= 0 }, scheduledAt)
        }
    }

    fun resend(id: Long) = viewModelScope.launch { engine.resend(id) }

    /** Toggles my reaction (the same emoji again removes it). */
    fun react(id: Long, emoji: String) = viewModelScope.launch { reactions.react(id, emoji) }

    // ---------------------------------------------------------------- search in this conversation

    /** null = the search bar is closed. */
    val searchQuery = MutableStateFlow<String?>(null)
    val searchHits = MutableStateFlow<List<ThreadHit>>(emptyList())
    /** Which result is shown (0 = the newest). */
    val searchIndex = MutableStateFlow(0)
    /** Position in the list of the shown result, to scroll to (-1 = none). */
    val searchTarget = MutableStateFlow(-1)

    init {
        viewModelScope.launch {
            searchQuery.debounce(220).collect { q ->
                val match = q?.let { TextNorm.ftsQuery(it) }
                searchHits.value = if (match == null) emptyList()
                else runCatching { messageDao.searchInThread(match, threadId) }.getOrDefault(emptyList())
                searchIndex.value = 0
                showHit()
            }
        }
    }

    fun openSearch() {
        if (searchQuery.value == null) searchQuery.value = ""
    }

    fun closeSearch() {
        searchQuery.value = null
        searchHits.value = emptyList()
        searchTarget.value = -1
    }

    fun onSearch(q: String) {
        searchQuery.value = q
    }

    /** Older result (up in the conversation). */
    fun olderHit() = moveHit(+1)

    /** Newer result (down in the conversation). */
    fun newerHit() = moveHit(-1)

    private fun moveHit(step: Int) {
        val hits = searchHits.value
        if (hits.isEmpty()) return
        searchIndex.value = (searchIndex.value + step).mod(hits.size)
        viewModelScope.launch { showHit() }
    }

    private suspend fun showHit() {
        val hit = searchHits.value.getOrNull(searchIndex.value)
        searchTarget.value = if (hit == null) -1 else messageDao.positionInThread(threadId, hit.date, hit.id)
    }
    fun deleteMessage(id: Long) = viewModelScope.launch { conversations.deleteMessage(id) }

    fun setStarred(id: Long, starred: Boolean) = viewModelScope.launch { messageDao.setStarred(id, starred) }
    fun cancelScheduled(id: Long) = viewModelScope.launch { engine.cancelScheduled(id) }
    fun setCategory(category: Category) = viewModelScope.launch { conversations.setCategoryForSender(threadId, category) }
    fun openNotificationSettings() = viewModelScope.launch { notifier.openConversationSettings(threadId) }

    fun togglePin() = viewModelScope.launch {
        conversation.value?.let { conversations.setPinned(listOf(threadId), !it.pinned) }
    }

    fun toggleMute() = viewModelScope.launch {
        conversation.value?.let { conversations.setMuted(listOf(threadId), !it.muted) }
    }

    fun archive(done: () -> Unit) = viewModelScope.launch {
        conversations.setArchived(listOf(threadId), true)
        done()
    }

    fun delete(done: () -> Unit) = viewModelScope.launch {
        conversations.delete(listOf(threadId))
        done()
    }

    fun block(done: () -> Unit) = viewModelScope.launch {
        conversations.block(listOf(threadId))
        done()
    }

    /** Waits for the conversation row (used when a thread is opened before it exists). */
    suspend fun awaitConversation(): ConversationEntity? = conversation.first()

    /**
     * Leaving the screen: the draft is saved at once (no waiting for the typing pause), and a
     * conversation opened from another app is removed if it was left empty.
     */
    override fun onCleared() {
        val last = text.value
        appScope.launch {
            conversations.setDraft(threadId, last)
            conversations.dropIfEmpty(threadId)
        }
        super.onCleared()
    }
}
