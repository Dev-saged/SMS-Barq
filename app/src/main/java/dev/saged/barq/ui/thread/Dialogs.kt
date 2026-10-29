package dev.saged.barq.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.saged.barq.R
import dev.saged.barq.data.db.Category
import dev.saged.barq.ui.components.icon
import dev.saged.barq.ui.components.label
import java.util.Calendar
import java.util.TimeZone

private fun at(dayOffset: Int, hour: Int, minute: Int = 0): Long =
    Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

@Composable
fun ScheduleDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var stage by remember { mutableStateOf(0) } // 0 quick list, 1 date, 2 time
    var pickedDay by remember { mutableStateOf<Long?>(null) }

    when (stage) {
        0 -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.schedule_send)) },
            text = {
                Column {
                    val tonight = at(0, 21).let { if (it <= System.currentTimeMillis()) at(1, 21) else it }
                    QuickOption(Icons.Outlined.Schedule, stringResource(R.string.in_one_hour)) {
                        onPick(System.currentTimeMillis() + 60 * 60 * 1000L)
                    }
                    QuickOption(Icons.Outlined.Nightlight, stringResource(R.string.tonight_9)) { onPick(tonight) }
                    QuickOption(Icons.Outlined.WbSunny, stringResource(R.string.tomorrow_9)) { onPick(at(1, 9)) }
                    QuickOption(Icons.Outlined.CalendarMonth, stringResource(R.string.pick_date_time)) { stage = 1 }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        1 -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(onClick = { pickedDay = state.selectedDateMillis; stage = 2 }) { Text(stringResource(R.string.next)) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
            ) { DatePicker(state) }
        }
        else -> {
            val now = Calendar.getInstance()
            val state = rememberTimePickerState(
                initialHour = (now.get(Calendar.HOUR_OF_DAY) + 1) % 24,
                initialMinute = 0,
                is24Hour = true,
            )
            AlertDialog(
                onDismissRequest = onDismiss,
                text = { TimePicker(state) },
                confirmButton = {
                    TextButton(onClick = {
                        // DatePicker returns UTC midnight of the chosen day.
                        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            timeInMillis = pickedDay ?: System.currentTimeMillis()
                        }
                        val local = Calendar.getInstance().apply {
                            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), state.hour, state.minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        val time = local.timeInMillis.coerceAtLeast(System.currentTimeMillis() + 60_000)
                        onPick(time)
                    }) { Text(stringResource(R.string.schedule)) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

@Composable
private fun QuickOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun CategoryDialog(current: Category, onDismiss: () -> Unit, onPick: (Category) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_category)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.set_category_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Category.entries.forEach { c ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(c) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = c == current, onClick = { onPick(c) })
                        Icon(c.icon(), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(c.label())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
