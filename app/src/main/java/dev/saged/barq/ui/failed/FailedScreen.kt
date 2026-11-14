package dev.saged.barq.ui.failed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import dev.saged.barq.ui.theme.islandColor
import dev.saged.barq.ui.theme.islandShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.R
import dev.saged.barq.data.ConversationRepository
import dev.saged.barq.data.db.BatchRow
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.sms.SendEngine
import dev.saged.barq.sms.SendErrors
import dev.saged.barq.ui.components.TimeFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FailedViewModel @Inject constructor(
    private val engine: SendEngine,
    private val conversations: ConversationRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val rows: StateFlow<List<BatchRow>> = engine.observeFailedRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val selection = MutableStateFlow<Set<Long>>(emptySet())

    fun toggle(id: Long) = selection.update { if (id in it) it - id else it + id }

    fun clearSelection() { selection.value = emptySet() }

    /** المحدد إن وُجد تحديد، وإلا كل الفاشلة. */
    private fun targets(): List<Long> =
        selection.value.ifEmpty { rows.value.map { it.id }.toSet() }.toList()

    fun resend() = viewModelScope.launch {
        engine.resendMany(targets())
        clearSelection()
    }

    fun resendOne(id: Long) = viewModelScope.launch { engine.resendMany(listOf(id)) }

    fun delete() = viewModelScope.launch {
        conversations.deleteMessages(targets())
        clearSelection()
    }

    fun targetCount(): Int = selection.value.size.takeIf { it > 0 } ?: rows.value.size
}

/** كل الرسائل الفاشلة في مكان واحد: إعادة إرسال أو حذف الرسالة الفاشلة فقط دون لمس المحادثة. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FailedScreen(onBack: () -> Unit, openThread: (Long) -> Unit, vm: FailedViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()
    val count = if (selecting) selection.size else rows.size

    androidx.activity.compose.BackHandler(enabled = selecting) { vm.clearSelection() }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                    }
                },
                title = {
                    Text(
                        if (selecting) stringResource(R.string.failed_selected, selection.size)
                        else stringResource(R.string.failed_title) + if (rows.isNotEmpty()) " · ${rows.size}" else "",
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
        bottomBar = {
            if (rows.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                        Text(
                            stringResource(if (selecting) R.string.failed_delete_n else R.string.failed_delete_all, count),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Button(onClick = vm::resend, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        Text(
                            stringResource(if (selecting) R.string.failed_resend_n else R.string.failed_resend_all, count),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.failed_empty), color = scheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(rows, key = { _, it -> it.id }, contentType = { _, _ -> "row" }) { i, row ->
                  Column(Modifier.padding(horizontal = 12.dp).clip(islandShape(i, rows.size)).background(islandColor())) {
                    FailedRowItem(
                        row = row,
                        settings = settings,
                        selected = row.id in selection,
                        selecting = selecting,
                        onClick = { if (selecting) vm.toggle(row.id) else openThread(row.threadId) },
                        onLongClick = { vm.toggle(row.id) },
                        onResend = { vm.resendOne(row.id) },
                    )
                    if (i < rows.size - 1) {
                        HorizontalDivider(Modifier.padding(start = 64.dp), color = scheme.outlineVariant.copy(alpha = 0.5f))
                    }
                  }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.failed_delete_title)) },
            text = { Text(stringResource(R.string.failed_delete_body, count)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) {
                    Text(stringResource(R.string.delete), color = scheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FailedRowItem(
    row: BatchRow,
    settings: AppSettings,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onResend: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) scheme.primaryContainer.copy(alpha = 0.5f) else scheme.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (selected) scheme.primary else scheme.error.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
            else Icon(Icons.Filled.Error, null, tint = scheme.error, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            val name = row.displayName?.takeIf { it.isNotBlank() } ?: row.address
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (name != row.address) {
                Text(
                    row.address,
                    style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr),
                    color = scheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(SendErrors.reason(row.errorCode)) + " · " + TimeFormat.clock(row.date, settings),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.error,
                maxLines = 2,
            )
        }
        if (!selecting) {
            IconButton(onClick = onResend) { Icon(Icons.Outlined.Refresh, stringResource(R.string.resend), tint = scheme.primary) }
        }
    }
}
