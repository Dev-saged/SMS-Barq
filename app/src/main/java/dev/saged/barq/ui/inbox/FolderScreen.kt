package dev.saged.barq.ui.inbox

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
import androidx.compose.material3.TopAppBar
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
import dev.saged.barq.data.db.ConversationDao
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.ui.components.ConversationRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FolderViewModel @Inject constructor(
    private val dao: ConversationDao,
    private val repository: ConversationRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    private val config = PagingConfig(pageSize = 40, enablePlaceholders = false)
    val archived = Pager(config) { dao.archived() }.flow.cachedIn(viewModelScope)
    val bin = Pager(config) { dao.recycleBin() }.flow.cachedIn(viewModelScope)
    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val selection = MutableStateFlow<Set<Long>>(emptySet())

    fun toggle(id: Long) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    private fun act(block: suspend (List<Long>) -> Unit) {
        val ids = selection.value.toList()
        selection.value = emptySet()
        if (ids.isNotEmpty()) viewModelScope.launch { block(ids) }
    }

    fun unarchive() = act { repository.setArchived(it, false) }
    fun restore() = act { repository.restore(it) }
    fun deleteForever() = act { repository.deleteForever(it) }
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
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (selection.isEmpty()) onBack() else { vm.selection.value = emptySet() }
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
                title = {
                    Text(
                        if (selection.isNotEmpty()) selection.size.toString()
                        else stringResource(if (bin) R.string.recycle_bin else R.string.archive)
                    )
                },
                actions = {
                    if (selection.isNotEmpty()) {
                        if (bin) {
                            IconButton(onClick = vm::restore) { Icon(Icons.Outlined.RestoreFromTrash, stringResource(R.string.restore)) }
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(Icons.Outlined.DeleteForever, stringResource(R.string.delete_forever))
                            }
                        } else {
                            IconButton(onClick = vm::unarchive) { Icon(Icons.Outlined.Unarchive, stringResource(R.string.unarchive)) }
                        }
                    } else if (bin && pagingItems.itemCount > 0) {
                        IconButton(onClick = { confirmEmpty = true }) {
                            Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.empty_bin))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
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
            items(count = pagingItems.itemCount, key = pagingItems.itemKey { it.threadId }) { i ->
                val c = pagingItems[i] ?: return@items
                ConversationRow(
                    conversation = c,
                    settings = settings,
                    selected = c.threadId in selection,
                    onClick = { if (selection.isNotEmpty() || bin) vm.toggle(c.threadId) else openThread(c.threadId) },
                    onLongClick = { vm.toggle(c.threadId) },
                )
            }
            if (pagingItems.itemCount == 0) {
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
