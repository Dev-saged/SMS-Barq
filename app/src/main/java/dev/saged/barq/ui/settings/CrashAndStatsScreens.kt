package dev.saged.barq.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.telephony.SmsMessage
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.R
import dev.saged.barq.data.db.Box
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.db.TopThread
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.sim.SimRepository
import dev.saged.barq.ui.components.TimeFormat
import dev.saged.barq.util.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject

// ================================================================================== crash log

@Composable
fun CrashLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(CrashLog.entries()) }
    var open by remember { mutableStateOf<String?>(null) }
    val copiedMsg = stringResource(R.string.crash_log_copied)
    val scheme = MaterialTheme.colorScheme
    androidx.compose.runtime.LaunchedEffect(Unit) { CrashLog.markSeen() }

    SettingsScaffold(title = stringResource(R.string.crash_log_title), onBack = onBack) {
        item {
            Text(
                stringResource(R.string.crash_log_hint),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            )
            if (entries.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    FilledTonalButton(onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("Barq crash log", CrashLog.exportText()))
                        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.copy), modifier = Modifier.padding(start = 6.dp))
                    }
                    FilledTonalButton(onClick = {
                        runCatching {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, CrashLog.exportText())
                            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) {
                        Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.share), modifier = Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = {
                        CrashLog.clear()
                        entries = emptyList()
                    }) {
                        Icon(Icons.Outlined.DeleteSweep, null, Modifier.size(18.dp), tint = scheme.error)
                        Text(stringResource(R.string.clear), color = scheme.error, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        if (entries.isEmpty()) item { EmptyHint(stringResource(R.string.crash_log_empty)) }
        items(entries, key = { it.file }) { e ->
            val expanded = open == e.file
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(scheme.surfaceContainerLow)
                    .clickable { open = if (expanded) null else e.file }
                    .animateContentSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(if (e.fatal) R.string.crash_fatal else R.string.crash_warning) +
                        " · " + TimeFormat.full(e.time, AppSettings()),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (e.fatal) scheme.error else scheme.primary,
                )
                Text(
                    e.title,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                )
                if (expanded) {
                    Text(
                        e.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

// ================================================================================== statistics

data class DayBar(val label: String, val incoming: Int, val outgoing: Int)

data class SimParts(val label: String, val parts: Int)

data class Stats(
    val today: Pair<Int, Int> = 0 to 0,
    val week: Pair<Int, Int> = 0 to 0,
    val month: Pair<Int, Int> = 0 to 0,
    val partsBySim: List<SimParts> = emptyList(),
    val failedPercent: Int = 0,
    val days: List<DayBar> = emptyList(),
    val top: List<TopThread> = emptyList(),
    val loaded: Boolean = false,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val messages: MessageDao,
    private val sims: SimRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val stats = MutableStateFlow(Stats())
    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    init {
        viewModelScope.launch { stats.value = withContext(Dispatchers.Default) { compute() } }
    }

    private fun startOfDay(daysAgo: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private suspend fun compute(): Stats {
        val monthStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = startOfDay(0)
        val week = startOfDay(6)
        val from = minOf(monthStart, week)
        val rows = messages.statRows(from)

        fun count(since: Long): Pair<Int, Int> {
            val r = rows.filter { it.date >= since }
            return r.count { it.box == Box.INBOX } to r.count { it.box == Box.SENT }
        }

        // SMS parts actually used this month (a long message counts as several SMS).
        val simList = sims.activeSims()
        val partsBySim = rows.filter { it.date >= monthStart && it.box == Box.SENT }
            .groupBy { it.subId }
            .map { (subId, list) ->
                val parts = list.sumOf { runCatching { SmsMessage.calculateLength(it.body, false)[0] }.getOrDefault(1) }
                val label = simList.firstOrNull { it.subId == subId }?.label ?: "SIM"
                SimParts(label, parts)
            }
            .sortedByDescending { it.parts }

        val monthOut = rows.filter { it.date >= monthStart && (it.box == Box.SENT || it.box == Box.FAILED) }
        val failedPercent = if (monthOut.isEmpty()) 0 else monthOut.count { it.box == Box.FAILED } * 100 / monthOut.size

        val dayNames = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        val days = (6 downTo 0).map { ago ->
            val start = startOfDay(ago)
            val end = start + 24 * 60 * 60 * 1000L
            val r = rows.filter { it.date in start until end }
            DayBar(dayNames.format(java.util.Date(start)), r.count { it.box == Box.INBOX }, r.count { it.box == Box.SENT })
        }

        return Stats(
            today = count(today),
            week = count(week),
            month = count(monthStart),
            partsBySim = partsBySim,
            failedPercent = failedPercent,
            days = days,
            top = messages.topThreads(monthStart, 5),
            loaded = true,
        )
    }
}

@Composable
fun StatsScreen(onBack: () -> Unit, openThread: (Long) -> Unit, vm: StatsViewModel = hiltViewModel()) {
    val s by vm.stats.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    SettingsScaffold(title = stringResource(R.string.stats_title), onBack = onBack) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatTile(stringResource(R.string.stats_today), s.today, Modifier.weight(1f))
                StatTile(stringResource(R.string.stats_week), s.week, Modifier.weight(1f))
                StatTile(stringResource(R.string.stats_month), s.month, Modifier.weight(1f))
            }
        }
        item {
            SettingsGroup(stringResource(R.string.stats_last_7)) {
                WeekChart(s.days)
            }
        }
        item {
            SettingsGroup(stringResource(R.string.stats_parts)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.partsBySim.isEmpty()) {
                        Text(stringResource(R.string.stats_none), color = scheme.onSurfaceVariant)
                    }
                    s.partsBySim.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.label, modifier = Modifier.weight(1f))
                            Text(
                                stringResource(R.string.stats_parts_value, p.parts),
                                fontWeight = FontWeight.Bold,
                                color = scheme.primary,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.stats_failed, s.failedPercent),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.failedPercent > 0) scheme.error else scheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.stats_top)) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    if (s.top.isEmpty()) {
                        Text(
                            stringResource(R.string.stats_none),
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    s.top.forEachIndexed { i, t ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openThread(t.threadId) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("${i + 1}", fontWeight = FontWeight.Bold, color = scheme.primary)
                            Text(t.name, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(stringResource(R.string.stats_messages, t.count), color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(title: String, value: Pair<Int, Int>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.primaryContainer)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer)
        Text(
            "${value.first + value.second}",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = scheme.onPrimaryContainer,
        )
        Text(
            stringResource(R.string.stats_in_out, value.first, value.second),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
    }
}

/** Seven days, received and sent side by side, drawn with Canvas (no chart library). */
@Composable
private fun WeekChart(days: List<DayBar>) {
    val scheme = MaterialTheme.colorScheme
    val inColor = scheme.tertiary
    val outColor = scheme.primary
    val max = (days.maxOfOrNull { maxOf(it.incoming, it.outgoing) } ?: 0).coerceAtLeast(1)
    Column(Modifier.padding(16.dp)) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            if (days.isEmpty()) return@Canvas
            val slot = size.width / days.size
            val bar = slot * 0.26f
            days.forEachIndexed { i, d ->
                // Right-to-left: today on the left edge reads naturally after the oldest on the right.
                val center = size.width - (i + 0.5f) * slot
                val hIn = size.height * d.incoming / max
                val hOut = size.height * d.outgoing / max
                drawRoundRect(
                    color = inColor,
                    topLeft = Offset(center - bar - 2f, size.height - hIn),
                    size = Size(bar, hIn),
                    cornerRadius = CornerRadius(8f, 8f),
                )
                drawRoundRect(
                    color = outColor,
                    topLeft = Offset(center + 2f, size.height - hOut),
                    size = Size(bar, hOut),
                    cornerRadius = CornerRadius(8f, 8f),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            days.forEach { d ->
                Text(
                    d.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
            Legend(inColor, stringResource(R.string.stats_received))
            Legend(outColor, stringResource(R.string.stats_sent))
        }
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
