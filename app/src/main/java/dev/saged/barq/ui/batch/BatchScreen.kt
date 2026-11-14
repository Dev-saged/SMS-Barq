package dev.saged.barq.ui.batch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import dev.saged.barq.ui.theme.islandColor
import dev.saged.barq.ui.theme.islandShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.R
import dev.saged.barq.data.db.BatchRow
import dev.saged.barq.data.db.Box
import dev.saged.barq.data.db.DeliveryStatus
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.sms.SendEngine
import dev.saged.barq.sms.SendErrors
import dev.saged.barq.ui.components.TimeFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class BatchFilter { ALL, SENT, DELIVERED, FAILED, PENDING }

private fun BatchRow.matches(f: BatchFilter): Boolean = when (f) {
    BatchFilter.ALL -> true
    BatchFilter.SENT -> box == Box.SENT
    BatchFilter.DELIVERED -> box == Box.SENT && deliveryStatus == DeliveryStatus.DELIVERED
    BatchFilter.FAILED -> box == Box.FAILED
    BatchFilter.PENDING -> box == Box.OUTBOX || box == Box.QUEUED
}

@HiltViewModel
class BatchViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val engine: SendEngine,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val batchId: Long = savedState.get<Long>("batchId") ?: 0L
    private val since: Long = savedState.get<Long>("since") ?: 0L

    /** No bulk-send id = what other apps sent (since [since]). */
    val external: Boolean = batchId <= 0L

    val rows: StateFlow<List<BatchRow>> =
        (if (external) engine.observeExternalRows(since) else engine.observeBatchRows(batchId))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val filter = MutableStateFlow(BatchFilter.ALL)

    fun resendFailed() = viewModelScope.launch {
        if (external) engine.resendFailedExternal(since) else engine.resendFailed(batchId)
    }

    fun resendOne(id: Long) = viewModelScope.launch { engine.resend(id) }
}

/** Every recipient of a bulk send (or of another app's send) with what happened to it. */
@Composable
fun BatchScreen(onBack: () -> Unit, openThread: (Long) -> Unit, vm: BatchViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val shown = remember(rows, filter) { rows.filter { it.matches(filter) } }
    val failed = remember(rows) { rows.count { it.box == Box.FAILED } }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
                title = { Text(stringResource(if (vm.external) R.string.external_title else R.string.batch_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
        bottomBar = {
            if (failed > 0) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    FilledTonalButton(onClick = vm::resendFailed, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.batch_resend_failed, failed), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val counts = remember(rows) { BatchFilter.entries.associateWith { f -> rows.count { it.matches(f) } } }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(BatchFilter.entries.toList(), key = { it.name }) { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { vm.filter.value = f },
                        label = { Text(stringResource(f.label()) + " · " + (counts[f] ?: 0)) },
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.empty_filtered),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(shown, key = { _, it -> it.id }) { i, row ->
                    Column(Modifier.padding(horizontal = 12.dp).clip(islandShape(i, shown.size)).background(islandColor())) {
                        BatchRowItem(row, settings, onClick = { openThread(row.threadId) }, onResend = { vm.resendOne(row.id) })
                        if (i < shown.size - 1) {
                            HorizontalDivider(Modifier.padding(start = 64.dp), color = scheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}

private fun BatchFilter.label(): Int = when (this) {
    BatchFilter.ALL -> R.string.all
    BatchFilter.SENT -> R.string.batch_sent
    BatchFilter.DELIVERED -> R.string.batch_delivered
    BatchFilter.FAILED -> R.string.batch_failed
    BatchFilter.PENDING -> R.string.batch_pending
}

@Composable
private fun BatchRowItem(row: BatchRow, settings: AppSettings, onClick: () -> Unit, onResend: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (icon, color, label) = status(row, settings)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)) }
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
            Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 2)
        }
        if (row.box == Box.FAILED) {
            IconButton(onClick = onResend) { Icon(Icons.Outlined.Refresh, stringResource(R.string.resend), tint = scheme.primary) }
        }
    }
}

@Composable
private fun status(row: BatchRow, settings: AppSettings): Triple<ImageVector, Color, String> {
    val scheme = MaterialTheme.colorScheme
    return when {
        row.box == Box.SENT && row.deliveryStatus == DeliveryStatus.DELIVERED ->
            Triple(Icons.Filled.DoneAll, scheme.primary, stringResource(R.string.status_delivered))
        row.box == Box.SENT && row.deliveryStatus == DeliveryStatus.FAILED ->
            Triple(Icons.Filled.Check, scheme.tertiary, stringResource(R.string.status_not_delivered))
        row.box == Box.SENT -> Triple(Icons.Filled.Check, scheme.secondary, stringResource(R.string.batch_sent))
        row.box == Box.FAILED -> Triple(
            Icons.Filled.Error, scheme.error,
            stringResource(R.string.batch_failed) + " · " + stringResource(SendErrors.reason(row.errorCode)),
        )
        row.box == Box.QUEUED && row.retryAt > 0 -> Triple(
            Icons.Outlined.Schedule, scheme.tertiary,
            stringResource(R.string.status_retrying, TimeFormat.clock(row.retryAt, settings), stringResource(SendErrors.reason(row.errorCode))),
        )
        row.box == Box.OUTBOX -> Triple(Icons.Outlined.Schedule, scheme.onSurfaceVariant, stringResource(R.string.status_sending))
        else -> Triple(Icons.Outlined.Schedule, scheme.onSurfaceVariant, stringResource(R.string.status_queued))
    }
}
