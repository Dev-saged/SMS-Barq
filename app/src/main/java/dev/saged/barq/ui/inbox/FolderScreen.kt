package dev.saged.barq.ui.inbox

import dev.saged.barq.ui.theme.islandShape
import dev.saged.barq.ui.theme.islandColor
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.R
import dev.saged.barq.data.ConversationRepository
import dev.saged.barq.data.db.Box as MessageBox
import dev.saged.barq.data.db.ConversationDao
import dev.saged.barq.data.db.ConversationEntity
import dev.saged.barq.data.db.TrashDao
import dev.saged.barq.data.db.TrashedMessage
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.ui.components.ConversationRow
import dev.saged.barq.ui.components.LocalBottomBarInset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FolderViewModel @Inject constructor(
    private val dao: ConversationDao,
    private val repository: ConversationRepository,
    trashDao: TrashDao,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    private val config = PagingConfig(pageSize = 40, enablePlaceholders = false)
    val archived = Pager(config) { dao.archived() }.flow.cachedIn(viewModelScope)
    val bin = Pager(config) { dao.recycleBin() }.flow.cachedIn(viewModelScope)
    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val selection = MutableStateFlow<Set<Long>>(emptySet())
    val trashed = trashDao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val messageSelection = MutableStateFlow<Set<Long>>(emptySet())

    fun toggleMessage(id: Long) {
        messageSelection.value = messageSelection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selection.value = emptySet()
        messageSelection.value = emptySet()
    }

    fun toggle(id: Long) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    private fun act(block: suspend (List<Long>) -> Unit) {
        val ids = selection.value.toList()
        selection.value = emptySet()
        if (ids.isNotEmpty()) viewModelScope.launch { block(ids) }
    }

    private fun actOnMessages(block: suspend (List<Long>) -> Unit) {
        val ids = messageSelection.value.toList()
        messageSelection.value = emptySet()
        if (ids.isNotEmpty()) viewModelScope.launch { block(ids) }
    }

    fun unarchive() = act { repository.setArchived(it, false) }
    fun restore() {
        act { repository.restore(it) }
        actOnMessages { repository.restoreMessages(it) }
    }

    fun deleteForever() {
        act { repository.deleteForever(it) }
        actOnMessages { repository.deleteTrashedForever(it) }
    }
    fun emptyBin() = viewModelScope.launch { repository.emptyRecycleBin() }
}

@Composable
fun FolderScreen(
    bin: Boolean,
    onBack: () -> Unit,
    openThread: (Long) -> Unit,
    vm: FolderViewModel = hiltViewModel(),
) {
    val pagingItems = (if (bin) vm.bin else vm.archived).collectAsLazyPagingItems()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val trashed by vm.trashed.collectAsStateWithLifecycle()
    val messageSelection by vm.messageSelection.collectAsStateWithLifecycle()
    val binMessages = if (bin) trashed else emptyList()
    val picked = selection.size + messageSelection.size
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (picked == 0) onBack() else vm.clearSelection()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
                title = {
                    Text(
                        if (picked > 0) picked.toString()
                        else stringResource(if (bin) R.string.recycle_bin else R.string.archive)
                    )
                },
                actions = {
                    if (picked > 0) {
                        if (bin) {
                            IconButton(onClick = vm::restore) { Icon(Icons.Outlined.RestoreFromTrash, stringResource(R.string.restore)) }
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(Icons.Outlined.DeleteForever, stringResource(R.string.delete_forever))
                            }
                        } else {
                            IconButton(onClick = vm::unarchive) { Icon(Icons.Outlined.Unarchive, stringResource(R.string.unarchive)) }
                        }
                    } else if (bin && (pagingItems.itemCount > 0 || binMessages.isNotEmpty())) {
                        IconButton(onClick = { confirmEmpty = true }) {
                            Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.empty_bin))
                        }
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp + LocalBottomBarInset.current),
        ) {
            if (bin) {
                item(key = "hint") {
                    Text(
                        stringResource(R.string.bin_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (binMessages.isNotEmpty()) {
                item(key = "messages-title") { BinSectionTitle(stringResource(R.string.bin_messages)) }
                items(count = binMessages.size, key = { "m" + binMessages[it].message.id }) { i ->
                    val t = binMessages[i]
                    Box(Modifier.padding(horizontal = 12.dp).clip(islandShape(i, binMessages.size))) {
                        ConversationRow(
                            conversation = t.asRow(),
                            settings = settings,
                            selected = t.message.id in messageSelection,
                            onClick = { vm.toggleMessage(t.message.id) },
                            onLongClick = { vm.toggleMessage(t.message.id) },
                            container = islandColor(),
                            divider = i < binMessages.size - 1,
                        )
                    }
                }
                if (pagingItems.itemCount > 0) {
                    item(key = "conversations-title") { BinSectionTitle(stringResource(R.string.bin_conversations)) }
                }
            }
            items(count = pagingItems.itemCount, key = pagingItems.itemKey { it.threadId }) { i ->
                val c = pagingItems[i] ?: return@items
                Box(Modifier.padding(horizontal = 12.dp).clip(islandShape(i, pagingItems.itemCount))) {
                    ConversationRow(
                        conversation = c,
                        settings = settings,
                        selected = c.threadId in selection,
                        onClick = { if (picked > 0 || bin) vm.toggle(c.threadId) else openThread(c.threadId) },
                        onLongClick = { vm.toggle(c.threadId) },
                        container = islandColor(),
                        divider = i < pagingItems.itemCount - 1,
                    )
                }
            }
            if (pagingItems.itemCount == 0 && binMessages.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 96.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Inventory2, null,
                            tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(56.dp),
                        )
                        Text(
                            stringResource(if (bin) R.string.bin_empty else R.string.archive_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (confirmEmpty) {
        ConfirmDialog(
            text = stringResource(R.string.confirm_empty_bin),
            onConfirm = { confirmEmpty = false; vm.emptyBin() },
            onDismiss = { confirmEmpty = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            text = stringResource(R.string.confirm_delete_forever),
            onConfirm = { confirmDelete = false; vm.deleteForever() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun BinSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** A binned message drawn like a conversation row: who, what, when. */
private fun TrashedMessage.asRow() = ConversationEntity(
    threadId = -message.id,
    addresses = message.address,
    displayName = displayName ?: message.address,
    snippet = message.body,
    date = message.date,
    subId = message.subId,
    lastFromMe = message.box != MessageBox.INBOX,
    lastBox = message.box,
)

@Composable
fun ConfirmDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
