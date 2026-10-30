package dev.saged.barq.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.R
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.sms.gateway.GatewayServer
import dev.saged.barq.sms.gateway.GatewayService
import dev.saged.barq.ui.Routes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeveloperViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val repo: SettingsRepository,
    server: GatewayServer,
) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val status = server.status

    fun update(block: suspend SettingsRepository.() -> Unit) = viewModelScope.launch { repo.block() }

    /** Turns the gateway on (creating its key the first time) or off. */
    fun setGateway(on: Boolean, failed: () -> Unit) = viewModelScope.launch {
        if (on) {
            if (repo.current().gatewayKey.isBlank()) repo.setGatewayKey(GatewayServer.newKey())
            repo.setGatewayEnabled(true)
            if (!GatewayService.start(context)) {
                repo.setGatewayEnabled(false)
                failed()
            }
        } else {
            repo.setGatewayEnabled(false) // the service sees it and stops
        }
    }

    fun newKey() = update { setGatewayKey(GatewayServer.newKey()) }
}

private enum class DevDialog { SPEED, PORT, NEW_KEY }

/**
 * Developer mode (unlocked by tapping the version 3 times): tools for people who know what they
 * are doing — the local gateway, sending speed and limit, auto-reply and the crash log — so the
 * normal settings stay simple.
 */
@Composable
fun DeveloperScreen(onBack: () -> Unit, open: (String) -> Unit, vm: DeveloperViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val st by vm.status.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<DevDialog?>(null) }
    val scheme = MaterialTheme.colorScheme
    val copiedMsg = stringResource(R.string.copied)
    val failedMsg = stringResource(R.string.gateway_start_failed)

    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("barq", text))
        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
    }

    val speedLabels = listOf(
        0L to stringResource(R.string.speed_max),
        100L to stringResource(R.string.speed_fast),
        250L to stringResource(R.string.speed_balanced),
        800L to stringResource(R.string.speed_safe),
    )
    val host = st.address ?: "IP"
    val url = "http://$host:${s.gatewayPort}/send"

    SettingsScaffold(title = stringResource(R.string.dev_title), onBack = onBack) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(scheme.surfaceContainer)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(scheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.DeveloperMode, null, tint = scheme.onTertiaryContainer, modifier = Modifier.size(20.dp)) }
                Text(
                    stringResource(R.string.dev_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---- Local gateway
        item {
            SettingsGroup(stringResource(R.string.gateway_title)) {
                SwitchRow(
                    Icons.Outlined.Wifi, stringResource(R.string.gateway_enable), stringResource(R.string.gateway_enable_sub),
                    s.gatewayEnabled,
                ) { on -> vm.setGateway(on) { Toast.makeText(context, failedMsg, Toast.LENGTH_LONG).show() } }
                AnimatedVisibility(s.gatewayEnabled) {
                    Column {
                        RowDivider()
                        GatewayStatus(st, url, onCopy = { copy(url) })
                    }
                }
                RowDivider()
                ClickRow(Icons.Outlined.Key, stringResource(R.string.gateway_key), s.gatewayKey.ifBlank { stringResource(R.string.gateway_key_none) }) {
                    if (s.gatewayKey.isNotBlank()) copy(s.gatewayKey)
                }
                RowDivider()
                ClickRow(Icons.Outlined.Refresh, stringResource(R.string.gateway_new_key), stringResource(R.string.gateway_new_key_sub)) {
                    dialog = DevDialog.NEW_KEY
                }
                RowDivider()
                ClickRow(Icons.Outlined.Numbers, stringResource(R.string.gateway_port), s.gatewayPort.toString()) { dialog = DevDialog.PORT }
                RowDivider()
                SwitchRow(
                    Icons.Outlined.FilterAlt, stringResource(R.string.gateway_dedupe), stringResource(R.string.gateway_dedupe_sub),
                    s.gatewayDedupe,
                ) { v -> vm.update { setGatewayDedupe(v) } }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.gateway_how)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.gateway_how_text), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    val key = s.gatewayKey.ifBlank { "KEY" }
                    CommandBox(
                        "curl -X POST $url -H \"X-Api-Key: $key\" -H \"Content-Type: application/json\" " +
                            "-d '{\"phone\":\"0599000000\",\"message\":\"Barq\"}'",
                    ) { copy(it) }
                    Text(stringResource(R.string.gateway_numbers_note), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }

        // ---- Sending
        item {
            SettingsGroup(stringResource(R.string.dev_sending)) {
                ClickRow(Icons.Outlined.Speed, stringResource(R.string.bulk_speed), speedLabels.firstOrNull { it.first == s.sendDelayMs }?.second) {
                    dialog = DevDialog.SPEED
                }
                RowDivider()
                ClickRow(Icons.Outlined.Tune, stringResource(R.string.send_limit_title), stringResource(R.string.send_limit_sub)) {
                    open(Routes.SEND_LIMIT)
                }
                RowDivider()
                ClickRow(
                    Icons.Outlined.SmartToy, stringResource(R.string.auto_reply_title),
                    stringResource(if (s.autoReplyEnabled) R.string.on else R.string.off),
                ) { open(Routes.AUTO_REPLY) }
            }
        }

        // ---- Diagnostics
        item {
            SettingsGroup(stringResource(R.string.dev_diagnostics)) {
                ClickRow(Icons.Outlined.BugReport, stringResource(R.string.crash_log_title), stringResource(R.string.crash_log_sub)) {
                    open(Routes.CRASHES)
                }
                RowDivider()
                ClickRow(Icons.Outlined.VisibilityOff, stringResource(R.string.dev_hide), stringResource(R.string.dev_hide_sub)) {
                    vm.update { setDevMode(false) }
                    onBack()
                }
            }
        }
    }

    when (dialog) {
        DevDialog.SPEED -> ChoiceDialog(stringResource(R.string.bulk_speed), speedLabels, s.sendDelayMs, { dialog = null }) { v ->
            dialog = null; vm.update { setSendDelayMs(v) }
        }
        DevDialog.PORT -> PortDialog(s.gatewayPort, { dialog = null }) { p ->
            dialog = null; vm.update { setGatewayPort(p) }
        }
        DevDialog.NEW_KEY -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.gateway_new_key)) },
            text = { Text(stringResource(R.string.gateway_new_key_confirm)) },
            confirmButton = { TextButton(onClick = { dialog = null; vm.newKey() }) { Text(stringResource(R.string.gateway_new_key_yes)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        null -> Unit
    }
}

@Composable
private fun GatewayStatus(st: GatewayServer.Status, url: String, onCopy: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (st.running) scheme.primary else scheme.error))
            Text(
                when {
                    st.running && st.address == null -> stringResource(R.string.gateway_no_network)
                    st.running -> stringResource(R.string.gateway_running)
                    st.error != null -> stringResource(R.string.gateway_error, st.error.orEmpty())
                    else -> stringResource(R.string.gateway_starting)
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surfaceContainerHighest)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                url,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy), tint = scheme.primary) }
        }
        Text(
            stringResource(R.string.gateway_counts, st.received, st.rejected),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PortDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val port = text.toIntOrNull()
    val valid = port != null && port in 1024..65535
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gateway_port)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(5) },
                singleLine = true,
                isError = !valid,
                supportingText = { Text(stringResource(R.string.gateway_port_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(port ?: current) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
