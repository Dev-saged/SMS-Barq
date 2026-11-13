package dev.saged.barq.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.AlarmOn
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.SwipeLeft
import androidx.compose.material.icons.outlined.SwipeRight
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.BuildConfig
import dev.saged.barq.R
import dev.saged.barq.data.backup.BackupManager
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.update.UpdateChecker
import dev.saged.barq.data.update.UpdateManager
import dev.saged.barq.data.settings.BackupFrequency
import dev.saged.barq.data.settings.LockMethod
import dev.saged.barq.ui.lock.AppLock
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.settings.SwipeAction
import dev.saged.barq.data.settings.ThemeMode
import dev.saged.barq.data.sim.SimRepository
import dev.saged.barq.sms.ScheduleManager
import dev.saged.barq.ui.Routes
import dev.saged.barq.ui.components.TimeFormat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    val repo: SettingsRepository,
    private val backup: BackupManager,
    private val scheduler: ScheduleManager,
    private val updates: UpdateManager,
    simRepository: SimRepository,
) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val sims = simRepository.activeSims()

    /** Settings found in an imported backup, waiting for the user's yes/no. */
    val pendingSettings = kotlinx.coroutines.flow.MutableStateFlow<AppSettings?>(null)

    /** Result of the last "check for updates" (null = nothing to show). */
    val update = kotlinx.coroutines.flow.MutableStateFlow<UpdateChecker.Result?>(null)
    val checking = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun checkUpdates() {
        if (checking.value) return
        checking.value = true
        viewModelScope.launch {
            // A newer version opens the update sheet (UpdateLayer); only the other outcomes show here.
            update.value = updates.check().takeUnless { it is UpdateChecker.Result.Available }
            checking.value = false
        }
    }

    fun clearUpdate() {
        update.value = null
    }

    fun applyPendingSettings() {
        val s = pendingSettings.value ?: return
        pendingSettings.value = null
        viewModelScope.launch { backup.applySettings(s) }
    }

    fun update(block: suspend SettingsRepository.() -> Unit) = viewModelScope.launch { repo.block() }

    fun canScheduleExact() = scheduler.canScheduleExact()

    fun export(uri: Uri, done: (Int?) -> Unit) = viewModelScope.launch {
        val n = runCatching { context.contentResolver.openOutputStream(uri)?.use { backup.export(it) } }.getOrNull()
        done(n)
    }

    fun exportXml(uri: Uri, done: (Int?) -> Unit) = viewModelScope.launch {
        val n = runCatching { context.contentResolver.openOutputStream(uri)?.use { backup.exportXml(it) } }.getOrNull()
        done(n)
    }

    fun xmlFileName() = backup.xmlFileName()

    fun import(uri: Uri, done: (Int?) -> Unit) = viewModelScope.launch {
        val result = runCatching { backup.import(uri) }.getOrNull()
        done(result?.imported)
        // The backup carries settings too: ask before replacing the current ones.
        if (result?.settings != null) pendingSettings.value = result.settings
    }

    fun setBackupFolder(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        update { setBackupTreeUri(uri.toString()) }
    }

    fun backupNow(done: (Boolean) -> Unit) = viewModelScope.launch { done(backup.runAutomaticBackup()) }

    fun backupFileName() = backup.backupFileName()
}

private enum class Dialog { THEME, FONT, DATE, SIM, SWIPE_START, SWIPE_END, BACKUP, LOCK_METHOD, LOCK_AFTER }

private tailrec fun android.content.Context.findActivity(): androidx.fragment.app.FragmentActivity? = when (this) {
    is androidx.fragment.app.FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val TAP_GAP_MS = 800L

@Composable
fun SettingsScreen(onBack: () -> Unit, open: (String) -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val pendingSettings by vm.pendingSettings.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<Dialog?>(null) }

    val exportedMsg = stringResource(R.string.export_done)
    val importedMsg = stringResource(R.string.import_done)
    val failedMsg = stringResource(R.string.operation_failed)
    val backupDoneMsg = stringResource(R.string.backup_now_done)
    val devUnlockedMsg = stringResource(R.string.dev_unlocked)
    var showWhatsNew by remember { mutableStateOf(false) }
    val lockUnavailable = stringResource(R.string.lock_unavailable)
    val activity = remember(context) { context.findActivity() }
    val lockMethods = listOf(
        LockMethod.ANY to stringResource(R.string.lock_any),
        LockMethod.BIOMETRIC to stringResource(R.string.lock_bio),
        LockMethod.DEVICE to stringResource(R.string.lock_pin),
    )
    val lockAfter = listOf(
        0 to stringResource(R.string.lock_now),
        60 to stringResource(R.string.lock_1m),
        300 to stringResource(R.string.lock_5m),
    )
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(dev.saged.barq.data.backup.BackupManager.MIME)) { uri ->
        if (uri != null) vm.export(uri) { n -> toast(if (n != null) String.format(exportedMsg, n) else failedMsg) }
    }
    val xmlExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(dev.saged.barq.data.backup.SmsXml.MIME)) { uri ->
        if (uri != null) vm.exportXml(uri) { n -> toast(if (n != null) String.format(exportedMsg, n) else failedMsg) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri) { n -> toast(if (n != null) String.format(importedMsg, n) else failedMsg) }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setBackupFolder(uri)
    }

    val themeLabels = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.theme_system),
        ThemeMode.LIGHT to stringResource(R.string.theme_light),
        ThemeMode.DARK to stringResource(R.string.theme_dark),
    )
    val fontLabels = listOf(
        0.9f to stringResource(R.string.font_small),
        1f to stringResource(R.string.font_normal),
        1.15f to stringResource(R.string.font_large),
        1.3f to stringResource(R.string.font_xlarge),
    )
    val datePatterns = listOf("dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "d MMMM yyyy").map { p ->
        p to java.text.SimpleDateFormat(p, java.util.Locale.getDefault()).format(java.util.Date())
    }
    val swipeLabels = listOf(
        SwipeAction.NONE to stringResource(R.string.swipe_none),
        SwipeAction.ARCHIVE to stringResource(R.string.archive),
        SwipeAction.DELETE to stringResource(R.string.delete),
        SwipeAction.READ to stringResource(R.string.swipe_read_toggle),
        SwipeAction.PIN to stringResource(R.string.pin),
        SwipeAction.MUTE to stringResource(R.string.mute),
        SwipeAction.CALL to stringResource(R.string.swipe_call),
    )
    val backupLabels = listOf(
        BackupFrequency.OFF to stringResource(R.string.backup_off),
        BackupFrequency.DAILY to stringResource(R.string.backup_daily),
        BackupFrequency.WEEKLY to stringResource(R.string.backup_weekly),
    )
    fun <T> label(list: List<Pair<T, String>>, v: T) = list.firstOrNull { it.first == v }?.second.orEmpty()

    SettingsScaffold(title = stringResource(R.string.settings), onBack = onBack, bottomPadding = 16.dp) {
        item {
            SettingsGroup(stringResource(R.string.group_appearance)) {
                ClickRow(Icons.Outlined.DarkMode, stringResource(R.string.theme), label(themeLabels, s.themeMode)) { dialog = Dialog.THEME }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    RowDivider()
                    SwitchRow(
                        Icons.Outlined.Palette, stringResource(R.string.material_you), stringResource(R.string.material_you_sub),
                        s.dynamicColor,
                    ) { v -> vm.update { setDynamicColor(v) } }
                }
                RowDivider()
                ClickRow(Icons.Outlined.FormatSize, stringResource(R.string.font_size), label(fontLabels, s.fontScale)) { dialog = Dialog.FONT }
                RowDivider()
                ClickRow(Icons.Outlined.CalendarMonth, stringResource(R.string.date_format), label(datePatterns, s.datePattern)) { dialog = Dialog.DATE }
                RowDivider()
                SwitchRow(Icons.Outlined.AccessTime, stringResource(R.string.use_24h), null, s.use24h) { v -> vm.update { setUse24h(v) } }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.group_messages)) {
                SwitchRow(
                    Icons.Outlined.DoneAll, stringResource(R.string.delivery_reports), stringResource(R.string.delivery_reports_sub),
                    s.deliveryReports,
                ) { v -> vm.update { setDeliveryReports(v) } }
                RowDivider()
                SwitchRow(
                    Icons.Outlined.Numbers, stringResource(R.string.char_counter), stringResource(R.string.char_counter_sub),
                    s.showCounter,
                ) { v -> vm.update { setShowCounter(v) } }
                if (vm.sims.size > 1) {
                    RowDivider()
                    val simName = vm.sims.firstOrNull { it.subId == s.defaultSubId }?.label ?: stringResource(R.string.ask_system)
                    ClickRow(Icons.Outlined.SimCard, stringResource(R.string.default_sim), simName) { dialog = Dialog.SIM }
                }
                RowDivider()
                SwitchRow(
                    Icons.Outlined.EmojiEmotions, stringResource(R.string.reactions_sms), stringResource(R.string.reactions_sms_sub),
                    s.reactionsAsSms,
                ) { v -> vm.update { setReactionsAsSms(v) } }
                RowDivider()
                SwitchRow(
                    Icons.Outlined.Image, stringResource(R.string.mms_receive), stringResource(R.string.mms_receive_sub),
                    s.mmsEnabled,
                ) { v -> vm.update { setMmsEnabled(v) } }
                if (!vm.canScheduleExact() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    RowDivider()
                    ClickRow(Icons.Outlined.AlarmOn, stringResource(R.string.exact_alarms), stringResource(R.string.exact_alarms_sub)) {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.group_protection)) {
                // App lock: the phone's own fingerprint / face / screen lock, confirmed before any change.
                SwitchRow(
                    Icons.Outlined.Fingerprint, stringResource(R.string.lock_setting), stringResource(R.string.lock_setting_sub),
                    s.lockEnabled,
                ) { on ->
                    val a = activity ?: return@SwitchRow
                    if (on && !AppLock.available(a, s.lockMethod)) {
                        toast(lockUnavailable)
                    } else {
                        AppLock.prompt(a, s.lockMethod) { ok ->
                            if (ok) {
                                if (on) AppLock.unlockNow()
                                vm.update { setLockEnabled(on) }
                            }
                        }
                    }
                }
                if (s.lockEnabled) {
                    RowDivider()
                    ClickRow(Icons.Outlined.Lock, stringResource(R.string.lock_method), label(lockMethods, s.lockMethod)) { dialog = Dialog.LOCK_METHOD }
                    RowDivider()
                    ClickRow(Icons.Outlined.Timer, stringResource(R.string.lock_after), label(lockAfter, s.lockAfterSec)) { dialog = Dialog.LOCK_AFTER }
                }
                RowDivider()
                SwitchRow(
                    Icons.Outlined.Shield, stringResource(R.string.spam_filter), stringResource(R.string.spam_filter_sub),
                    s.spamFilter,
                ) { v -> vm.update { setSpamFilter(v) } }
                RowDivider()
                ClickRow(Icons.Outlined.Block, stringResource(R.string.blocked_title), stringResource(R.string.blocked_sub)) { open(Routes.BLOCKED) }
                RowDivider()
                ClickRow(Icons.Outlined.Category, stringResource(R.string.rules_title), stringResource(R.string.rules_sub)) { open(Routes.RULES) }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.group_tools)) {
                ClickRow(Icons.Outlined.Bolt, stringResource(R.string.templates_title), stringResource(R.string.templates_sub)) { open(Routes.TEMPLATES) }
                RowDivider()
                ClickRow(Icons.Outlined.Groups, stringResource(R.string.groups_title), stringResource(R.string.groups_sub)) {
                    open(Routes.GROUPS)
                }
                RowDivider()
                ClickRow(Icons.Outlined.BarChart, stringResource(R.string.stats_title), stringResource(R.string.stats_sub)) {
                    open(Routes.STATS)
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.group_gestures)) {
                ClickRow(Icons.Outlined.SwipeLeft, stringResource(R.string.swipe_start), label(swipeLabels, s.swipeStart)) { dialog = Dialog.SWIPE_START }
                RowDivider()
                ClickRow(Icons.Outlined.SwipeRight, stringResource(R.string.swipe_end), label(swipeLabels, s.swipeEnd)) { dialog = Dialog.SWIPE_END }
                RowDivider()
                SwitchRow(
                    Icons.Outlined.Delete, stringResource(R.string.recycle_bin), stringResource(R.string.recycle_bin_sub),
                    s.recycleBin,
                ) { v -> vm.update { setRecycleBin(v) } }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.group_backup)) {
                ClickRow(Icons.Outlined.Upload, stringResource(R.string.export_messages), stringResource(R.string.export_sub)) {
                    exportLauncher.launch(vm.backupFileName())
                }
                RowDivider()
                ClickRow(Icons.Outlined.Upload, stringResource(R.string.export_xml), stringResource(R.string.export_xml_sub)) {
                    xmlExportLauncher.launch(vm.xmlFileName())
                }
                RowDivider()
                ClickRow(Icons.Outlined.Download, stringResource(R.string.import_messages), stringResource(R.string.import_sub)) {
                    importLauncher.launch(arrayOf("application/gzip", "application/x-gzip", "application/json", "text/plain", "*/*"))
                }
                RowDivider()
                ClickRow(Icons.Outlined.Backup, stringResource(R.string.auto_backup), label(backupLabels, s.backupFrequency)) {
                    dialog = Dialog.BACKUP
                }
                RowDivider()
                val folder = s.backupTreeUri?.let { Uri.decode(it).substringAfterLast(':').ifBlank { it } }
                    ?: stringResource(R.string.backup_folder_default)
                ClickRow(Icons.Outlined.FolderOpen, stringResource(R.string.backup_folder), folder) { folderLauncher.launch(null) }
                RowDivider()
                val last = if (s.lastBackupAt > 0) TimeFormat.full(s.lastBackupAt, s) else stringResource(R.string.never)
                ClickRow(Icons.Outlined.AutoAwesome, stringResource(R.string.backup_now), stringResource(R.string.last_backup, last)) {
                    vm.backupNow { ok -> toast(if (ok) backupDoneMsg else failedMsg) }
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.group_about)) {
                ClickRow(Icons.Outlined.VerifiedUser, stringResource(R.string.health_title), stringResource(R.string.health_sub)) {
                    open(Routes.HEALTH)
                }
                RowDivider()
                // Tapping the version 3 times in a row unlocks developer mode.
                ClickRow(Icons.Outlined.Info, stringResource(R.string.app_name), stringResource(R.string.version_value, BuildConfig.VERSION_NAME)) {
                    val now = SystemClock.uptimeMillis()
                    taps = if (now - lastTap < TAP_GAP_MS) taps + 1 else 1
                    lastTap = now
                    if (taps >= 3) {
                        taps = 0
                        if (!s.devMode) {
                            vm.update { setDevMode(true) }
                            toast(devUnlockedMsg)
                        }
                        open(Routes.DEVELOPER)
                    }
                }
                RowDivider()
                ClickRow(Icons.Outlined.NewReleases, stringResource(R.string.wn_settings), stringResource(R.string.version_value, BuildConfig.VERSION_NAME)) {
                    showWhatsNew = true
                }
                RowDivider()
                ClickRow(
                    Icons.Outlined.SystemUpdate, stringResource(R.string.check_updates),
                    stringResource(if (checking) R.string.checking else R.string.check_updates_sub),
                ) { vm.checkUpdates() }
                if (s.devMode) {
                    RowDivider()
                    ClickRow(Icons.Outlined.DeveloperMode, stringResource(R.string.dev_open), stringResource(R.string.dev_open_sub)) {
                        open(Routes.DEVELOPER)
                    }
                }
            }
        }
        // The very last thing in Settings, with nothing under it.
        item { AboutCard() }
    }

    if (showWhatsNew) dev.saged.barq.ui.whatsnew.WhatsNewSheet(onClose = { showWhatsNew = false })
    if (pendingSettings != null) {
        AlertDialog(
            onDismissRequest = { vm.pendingSettings.value = null },
            title = { Text(stringResource(R.string.restore_settings_title)) },
            text = { Text(stringResource(R.string.restore_settings_text)) },
            confirmButton = { TextButton(onClick = vm::applyPendingSettings) { Text(stringResource(R.string.restore_settings_yes)) } },
            dismissButton = { TextButton(onClick = { vm.pendingSettings.value = null }) { Text(stringResource(R.string.restore_settings_no)) } },
        )
    }
    when (val u = update) {
        is UpdateChecker.Result.Available -> Unit
        is UpdateChecker.Result.UpToDate -> {
            val msg = stringResource(R.string.update_none)
            androidx.compose.runtime.LaunchedEffect(u) {
                toast(msg)
                vm.clearUpdate()
            }
        }
        is UpdateChecker.Result.Failed -> AlertDialog(
            onDismissRequest = vm::clearUpdate,
            title = { Text(stringResource(R.string.update_failed)) },
            text = { Text(stringResource(R.string.update_failed_reason, u.reason), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = vm::clearUpdate) { Text(stringResource(android.R.string.ok)) } },
        )
        null -> Unit
    }

    when (dialog) {
        Dialog.THEME -> ChoiceDialog(stringResource(R.string.theme), themeLabels, s.themeMode, { dialog = null }) { v ->
            dialog = null; vm.update { setThemeMode(v) }
        }
        Dialog.FONT -> ChoiceDialog(stringResource(R.string.font_size), fontLabels, s.fontScale, { dialog = null }) { v ->
            dialog = null; vm.update { setFontScale(v) }
        }
        Dialog.DATE -> ChoiceDialog(stringResource(R.string.date_format), datePatterns, s.datePattern, { dialog = null }) { v ->
            dialog = null; vm.update { setDatePattern(v) }
        }
        Dialog.SIM -> {
            val options = listOf(-1 to stringResource(R.string.ask_system)) + vm.sims.map { it.subId to it.label }
            ChoiceDialog(stringResource(R.string.default_sim), options, s.defaultSubId, { dialog = null }) { v ->
                dialog = null; vm.update { setDefaultSubId(v) }
            }
        }
        Dialog.SWIPE_START -> ChoiceDialog(stringResource(R.string.swipe_start), swipeLabels, s.swipeStart, { dialog = null }) { v ->
            dialog = null; vm.update { setSwipeStart(v) }
        }
        Dialog.SWIPE_END -> ChoiceDialog(stringResource(R.string.swipe_end), swipeLabels, s.swipeEnd, { dialog = null }) { v ->
            dialog = null; vm.update { setSwipeEnd(v) }
        }
        Dialog.LOCK_METHOD -> ChoiceDialog(stringResource(R.string.lock_method), lockMethods, s.lockMethod, { dialog = null }) { v ->
            dialog = null
            val a = activity
            if (a != null && !AppLock.available(a, v)) toast(lockUnavailable)
            else if (a != null) AppLock.prompt(a, v) { ok -> if (ok) vm.update { setLockMethod(v) } }
        }
        Dialog.LOCK_AFTER -> ChoiceDialog(stringResource(R.string.lock_after), lockAfter, s.lockAfterSec, { dialog = null }) { v ->
            dialog = null; vm.update { setLockAfter(v) }
        }
        Dialog.BACKUP -> ChoiceDialog(stringResource(R.string.auto_backup), backupLabels, s.backupFrequency, { dialog = null }) { v ->
            dialog = null; vm.update { setBackupFrequency(v) }
        }
        null -> Unit
    }
}
