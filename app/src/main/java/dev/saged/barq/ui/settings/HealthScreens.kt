package dev.saged.barq.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.R
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.sim.SimRepository
import dev.saged.barq.sms.SendLimit
import dev.saged.barq.ui.Routes
import dev.saged.barq.ui.components.rememberDefaultSmsRequest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HealthViewModel @Inject constructor(
    val limit: SendLimit,
    private val sims: SimRepository,
    private val repo: SettingsRepository,
) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    fun simCount() = sims.activeSims().size
    fun update(block: suspend SettingsRepository.() -> Unit) = viewModelScope.launch { repo.block() }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isDefaultSms(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
    } else {
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * "Is Barq ready?" — every condition a reliable SMS app needs, each with a fix button. Made for
 * people who do not know what a permission or battery optimisation is.
 */
@Composable
fun HealthScreen(onBack: () -> Unit, open: (String) -> Unit, vm: HealthViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val requestDefault = rememberDefaultSmsRequest { tick++ }

    // Read again every time the screen comes back (after the user fixed something).
    val checks = remember(tick, s) {
        val pm = context.getSystemService(PowerManager::class.java)
        val limit = vm.limit.current()
        listOf(
            Check(R.string.health_default, isDefaultSms(context), R.string.health_default_fix) { requestDefault() },
            Check(
                R.string.health_sms,
                granted(context, Manifest.permission.READ_SMS) && granted(context, Manifest.permission.SEND_SMS) &&
                    granted(context, Manifest.permission.RECEIVE_SMS),
                R.string.health_open_settings,
            ) { openAppSettings(context) },
            Check(R.string.health_contacts, granted(context, Manifest.permission.READ_CONTACTS), R.string.health_open_settings) {
                openAppSettings(context)
            },
            Check(R.string.health_phone, granted(context, Manifest.permission.READ_PHONE_STATE), R.string.health_open_settings) {
                openAppSettings(context)
            },
            Check(R.string.health_notifications, NotificationManagerCompat.from(context).areNotificationsEnabled(), R.string.health_open_settings) {
                runCatching {
                    val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                    context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            Check(R.string.health_battery, pm?.isIgnoringBatteryOptimizations(context.packageName) == true, R.string.health_battery_fix) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            Check(
                R.string.health_exact,
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true,
                R.string.health_open_settings,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            Check(R.string.health_limit, limit.raised, R.string.health_limit_fix, optional = true) { open(Routes.SEND_LIMIT) },
            Check(R.string.health_delivery, s.deliveryReports, R.string.health_turn_on, optional = true) {
                vm.update { setDeliveryReports(true) }
            },
        )
    }
    val sims = remember(tick) { vm.simCount() }
    val problems = checks.count { !it.ok && !it.optional }

    SettingsScaffold(title = stringResource(R.string.health_title), onBack = onBack) {
        item {
            Summary(
                ok = problems == 0,
                text = if (problems == 0) stringResource(R.string.health_all_good) else stringResource(R.string.health_problems, problems),
            )
        }
        item {
            SettingsGroup(stringResource(R.string.health_checks)) {
                checks.forEachIndexed { i, c ->
                    if (i > 0) RowDivider()
                    CheckRow(c)
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.health_info)) {
                InfoRow(Icons.Outlined.Info, stringResource(R.string.health_sims, sims))
            }
        }
    }
}

private class Check(
    val title: Int,
    val ok: Boolean,
    val fixLabel: Int,
    /** Not needed for Barq to work, only nice to have (shown as a suggestion, not a problem). */
    val optional: Boolean = false,
    val fix: () -> Unit,
)

@Composable
private fun Summary(ok: Boolean, text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (ok) scheme.primaryContainer else scheme.errorContainer)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null,
            tint = if (ok) scheme.onPrimaryContainer else scheme.onErrorContainer,
            modifier = Modifier.size(26.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (ok) scheme.onPrimaryContainer else scheme.onErrorContainer,
        )
    }
}

@Composable
private fun CheckRow(c: Check) {
    val scheme = MaterialTheme.colorScheme
    val color = when {
        c.ok -> scheme.primary
        c.optional -> scheme.tertiary
        else -> scheme.error
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (c.ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null, tint = color, modifier = Modifier.size(22.dp))
        Text(stringResource(c.title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!c.ok) {
            Text(
                stringResource(c.fixLabel),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = c.fix).padding(6.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Android's SMS sending limit: what it is now, and how to raise it so big sends never stop.
 * Raising needs a permission only the owner can give (one adb command, also possible from the
 * phone itself with wireless debugging); after that it is one button.
 */
@Composable
fun SendLimitScreen(onBack: () -> Unit, vm: HealthViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val limit = remember(tick) { vm.limit.current() }
    val canChange = remember(tick) { vm.limit.canChange() }
    val scheme = MaterialTheme.colorScheme
    val doneMsg = stringResource(R.string.limit_changed)
    val failedMsg = stringResource(R.string.operation_failed)
    val copiedMsg = stringResource(R.string.copied)

    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("adb", text))
        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
    }

    SettingsScaffold(title = stringResource(R.string.send_limit_title), onBack = onBack) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (limit.raised) scheme.primaryContainer else scheme.surfaceContainer)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.limit_now), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                Text(
                    if (limit.raised) stringResource(R.string.limit_raised_value, limit.maxCount)
                    else stringResource(R.string.limit_value, limit.perMinute),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(if (limit.raised) R.string.limit_raised_explain else R.string.limit_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        item {
            if (canChange) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        Toast.makeText(context, if (vm.limit.raise()) doneMsg else failedMsg, Toast.LENGTH_SHORT).show()
                        tick++
                    }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.limit_raise)) }
                    OutlinedButton(onClick = {
                        Toast.makeText(context, if (vm.limit.restore()) doneMsg else failedMsg, Toast.LENGTH_SHORT).show()
                        tick++
                    }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.limit_restore)) }
                }
            } else {
                SettingsGroup(stringResource(R.string.limit_how_title)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(
                            R.string.limit_step1, R.string.limit_step2, R.string.limit_step3,
                            R.string.limit_step4, R.string.limit_step5,
                        ).forEachIndexed { i, step -> Step(i + 1, stringResource(step)) }
                        CommandBox(SendLimit.grantCommand(context.packageName)) { copy(it) }
                        Text(
                            stringResource(R.string.limit_step6),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Text(stringResource(R.string.limit_alt), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        CommandBox(SendLimit.directCommand()) { copy(it) }
                    }
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.limit_behaviour)) {
                SwitchRow(
                    Icons.Outlined.Info, stringResource(R.string.respect_limit), stringResource(R.string.respect_limit_sub),
                    s.respectLimit,
                ) { v -> vm.update { setRespectLimit(v) } }
                RowDivider()
                SwitchRow(
                    Icons.Outlined.Info, stringResource(R.string.auto_retry), stringResource(R.string.auto_retry_sub),
                    s.autoRetry,
                ) { v -> vm.update { setAutoRetry(v) } }
            }
        }
        item {
            Text(
                stringResource(R.string.limit_warning),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Text(n.toString(), style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer) }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CommandBox(command: String, onCopy: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerHighest)
            .clickable { onCopy(command) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            command,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy), tint = scheme.primary, modifier = Modifier.size(18.dp))
    }
}
