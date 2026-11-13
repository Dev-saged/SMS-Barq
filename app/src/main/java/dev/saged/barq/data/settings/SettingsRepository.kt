package dev.saged.barq.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class SwipeAction { NONE, ARCHIVE, DELETE, READ, PIN, MUTE, CALL }
enum class AutoReplyMode { FIXED, SMART }
enum class BackupFrequency { OFF, DAILY, WEEKLY }
/** How Barq is unlocked: fingerprint/face or the phone's PIN, only fingerprint/face, or only the PIN. */
enum class LockMethod { ANY, BIOMETRIC, DEVICE }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Off by default since 1.4.0: Barq's own colours look the same on every phone (Material You is optional). */
    val dynamicColor: Boolean = false,
    /** The floating bottom navigation bar between Messages, Starred, Archive and Settings. */
    val pillNav: Boolean = true,
    val fontScale: Float = 1f,
    val datePattern: String = "dd/MM/yyyy",
    val use24h: Boolean = true,
    /** On by default since 1.3.0: "delivered" marks need it (an explicit "off" is kept). */
    val deliveryReports: Boolean = true,
    val showCounter: Boolean = true,
    val mmsEnabled: Boolean = false,
    val spamFilter: Boolean = true,
    val recycleBin: Boolean = true,
    val swipeStart: SwipeAction = SwipeAction.ARCHIVE,
    val swipeEnd: SwipeAction = SwipeAction.DELETE,
    val defaultSubId: Int = -1,
    /** Gap between messages in a bulk send; -1 = automatic. */
    val sendDelayMs: Long = -1,
    val autoReplyEnabled: Boolean = false,
    val autoReplyMode: AutoReplyMode = AutoReplyMode.FIXED,
    val autoReplyText: String = "أنا مشغول حالياً، سأرد عليك في أقرب وقت.",
    val autoReplyContactsOnly: Boolean = false,
    val autoReplyCooldownMin: Int = 60,
    val autoReplyWindow: Boolean = false,
    val autoReplyFromMin: Int = 22 * 60,
    val autoReplyToMin: Int = 7 * 60,
    val backupFrequency: BackupFrequency = BackupFrequency.OFF,
    val backupTreeUri: String? = null,
    val lastBackupAt: Long = 0,
    val onboardingDone: Boolean = false,
    /** Last bulk send, shown as a progress card until dismissed (0 = none). Not backed up. */
    val lastBatchId: Long = 0,
    /** Bulk-send explanation (Android asks once to allow many messages) was shown. */
    val bulkIntroShown: Boolean = false,
    /** Temporary failures (no signal, Android's limit…) are retried automatically. */
    val autoRetry: Boolean = true,
    /** Stay under Android's sending limit instead of letting it stop the queue. */
    val respectLimit: Boolean = true,
    /** Also send my reactions as a short SMS ("❤️ on “…”") so the other person sees them. */
    val reactionsAsSms: Boolean = false,
    /** Messages other apps sent before this time are no longer shown on the inbox card. */
    val externalSeenAt: Long = 0,
    /** Hidden developer tools (unlocked by tapping the version 3 times). */
    val devMode: Boolean = false,
    /** Barq's local SMS gateway (LAN-only HTTP server, SMS Sendr compatible). */
    val gatewayEnabled: Boolean = false,
    val gatewayPort: Int = 8080,
    /** 32 hex characters; empty until the gateway is first turned on. */
    val gatewayKey: String = "",
    /** The same text to the same number within a few minutes is answered as a duplicate (409). */
    val gatewayDedupe: Boolean = true,
    /** The gateway's current burst, shown on its own inbox card (0 = none). Not backed up. */
    val gatewayBatchId: Long = 0,
    /** App lock with the phone's own fingerprint / face / screen lock. */
    val lockEnabled: Boolean = false,
    val lockMethod: LockMethod = LockMethod.ANY,
    /** Seconds away from Barq before it asks again (0 = every time). */
    val lockAfterSec: Int = 0,
    /** Last version whose "what's new" was shown (empty on a fresh install). */
    val seenVersion: String = "",
    /** Version of the classification rules last applied to old conversations. */
    val classVersion: Int = 0,
    /**
     * True once this install looked for a settings snapshot from Android's backup (1.5.3+).
     * Never backed up itself, so a new phone or a reinstall starts with false.
     */
    val snapshotChecked: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object K {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val pillNav = booleanPreferencesKey("pill_nav")
        val fontScale = floatPreferencesKey("font_scale")
        val datePattern = stringPreferencesKey("date_pattern")
        val use24h = booleanPreferencesKey("use_24h")
        val deliveryReports = booleanPreferencesKey("delivery_reports")
        val showCounter = booleanPreferencesKey("show_counter")
        val mmsEnabled = booleanPreferencesKey("mms_enabled")
        val spamFilter = booleanPreferencesKey("spam_filter")
        val recycleBin = booleanPreferencesKey("recycle_bin")
        val swipeStart = stringPreferencesKey("swipe_start")
        val swipeEnd = stringPreferencesKey("swipe_end")
        val defaultSubId = intPreferencesKey("default_sub_id")
        val sendDelayMs = longPreferencesKey("send_delay_ms")
        val arEnabled = booleanPreferencesKey("ar_enabled")
        val arMode = stringPreferencesKey("ar_mode")
        val arText = stringPreferencesKey("ar_text")
        val arContactsOnly = booleanPreferencesKey("ar_contacts_only")
        val arCooldown = intPreferencesKey("ar_cooldown")
        val arWindow = booleanPreferencesKey("ar_window")
        val arFrom = intPreferencesKey("ar_from")
        val arTo = intPreferencesKey("ar_to")
        val backupFrequency = stringPreferencesKey("backup_frequency")
        val backupTreeUri = stringPreferencesKey("backup_tree_uri")
        val lastBackupAt = longPreferencesKey("last_backup_at")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val lastBatchId = longPreferencesKey("last_batch_id")
        val bulkIntroShown = booleanPreferencesKey("bulk_intro_shown")
        val autoRetry = booleanPreferencesKey("auto_retry")
        val respectLimit = booleanPreferencesKey("respect_limit")
        val reactionsAsSms = booleanPreferencesKey("reactions_as_sms")
        val externalSeenAt = longPreferencesKey("external_seen_at")
        val devMode = booleanPreferencesKey("dev_mode")
        val gatewayEnabled = booleanPreferencesKey("gateway_enabled")
        val gatewayPort = intPreferencesKey("gateway_port")
        val gatewayKey = stringPreferencesKey("gateway_key")
        val gatewayDedupe = booleanPreferencesKey("gateway_dedupe")
        val gatewayBatchId = longPreferencesKey("gateway_batch_id")
        val lockEnabled = booleanPreferencesKey("lock_enabled")
        val lockMethod = stringPreferencesKey("lock_method")
        val lockAfter = intPreferencesKey("lock_after")
        val seenVersion = stringPreferencesKey("seen_version")
        val classVersion = intPreferencesKey("class_version")
        val snapshotChecked = booleanPreferencesKey("snapshot_checked")
    }

    private inline fun <reified T : Enum<T>> Preferences.enumOf(key: Preferences.Key<String>, default: T): T =
        this[key]?.let { name -> runCatching { enumValueOf<T>(name) }.getOrNull() } ?: default

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            themeMode = p.enumOf(K.themeMode, d.themeMode),
            dynamicColor = p[K.dynamicColor] ?: d.dynamicColor,
            pillNav = p[K.pillNav] ?: d.pillNav,
            fontScale = p[K.fontScale] ?: d.fontScale,
            datePattern = p[K.datePattern] ?: d.datePattern,
            use24h = p[K.use24h] ?: d.use24h,
            deliveryReports = p[K.deliveryReports] ?: d.deliveryReports,
            showCounter = p[K.showCounter] ?: d.showCounter,
            mmsEnabled = p[K.mmsEnabled] ?: d.mmsEnabled,
            spamFilter = p[K.spamFilter] ?: d.spamFilter,
            recycleBin = p[K.recycleBin] ?: d.recycleBin,
            swipeStart = p.enumOf(K.swipeStart, d.swipeStart),
            swipeEnd = p.enumOf(K.swipeEnd, d.swipeEnd),
            defaultSubId = p[K.defaultSubId] ?: d.defaultSubId,
            sendDelayMs = p[K.sendDelayMs] ?: d.sendDelayMs,
            autoReplyEnabled = p[K.arEnabled] ?: d.autoReplyEnabled,
            autoReplyMode = p.enumOf(K.arMode, d.autoReplyMode),
            autoReplyText = p[K.arText] ?: context.getString(dev.saged.barq.R.string.auto_reply_default_text),
            autoReplyContactsOnly = p[K.arContactsOnly] ?: d.autoReplyContactsOnly,
            autoReplyCooldownMin = p[K.arCooldown] ?: d.autoReplyCooldownMin,
            autoReplyWindow = p[K.arWindow] ?: d.autoReplyWindow,
            autoReplyFromMin = p[K.arFrom] ?: d.autoReplyFromMin,
            autoReplyToMin = p[K.arTo] ?: d.autoReplyToMin,
            backupFrequency = p.enumOf(K.backupFrequency, d.backupFrequency),
            backupTreeUri = p[K.backupTreeUri],
            lastBackupAt = p[K.lastBackupAt] ?: d.lastBackupAt,
            onboardingDone = p[K.onboardingDone] ?: d.onboardingDone,
            lastBatchId = p[K.lastBatchId] ?: d.lastBatchId,
            bulkIntroShown = p[K.bulkIntroShown] ?: d.bulkIntroShown,
            autoRetry = p[K.autoRetry] ?: d.autoRetry,
            respectLimit = p[K.respectLimit] ?: d.respectLimit,
            reactionsAsSms = p[K.reactionsAsSms] ?: d.reactionsAsSms,
            externalSeenAt = p[K.externalSeenAt] ?: d.externalSeenAt,
            devMode = p[K.devMode] ?: d.devMode,
            gatewayEnabled = p[K.gatewayEnabled] ?: d.gatewayEnabled,
            gatewayPort = p[K.gatewayPort] ?: d.gatewayPort,
            gatewayKey = p[K.gatewayKey] ?: d.gatewayKey,
            gatewayDedupe = p[K.gatewayDedupe] ?: d.gatewayDedupe,
            gatewayBatchId = p[K.gatewayBatchId] ?: d.gatewayBatchId,
            lockEnabled = p[K.lockEnabled] ?: d.lockEnabled,
            lockMethod = p.enumOf(K.lockMethod, d.lockMethod),
            lockAfterSec = p[K.lockAfter] ?: d.lockAfterSec,
            seenVersion = p[K.seenVersion] ?: d.seenVersion,
            classVersion = p[K.classVersion] ?: d.classVersion,
            snapshotChecked = p[K.snapshotChecked] ?: d.snapshotChecked,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setThemeMode(v: ThemeMode) = set(K.themeMode, v.name)
    suspend fun setDynamicColor(v: Boolean) = set(K.dynamicColor, v)
    suspend fun setPillNav(v: Boolean) = set(K.pillNav, v)
    suspend fun setFontScale(v: Float) = set(K.fontScale, v)
    suspend fun setDatePattern(v: String) = set(K.datePattern, v)
    suspend fun setUse24h(v: Boolean) = set(K.use24h, v)
    suspend fun setDeliveryReports(v: Boolean) = set(K.deliveryReports, v)
    suspend fun setShowCounter(v: Boolean) = set(K.showCounter, v)
    suspend fun setMmsEnabled(v: Boolean) = set(K.mmsEnabled, v)
    suspend fun setSpamFilter(v: Boolean) = set(K.spamFilter, v)
    suspend fun setRecycleBin(v: Boolean) = set(K.recycleBin, v)
    suspend fun setSwipeStart(v: SwipeAction) = set(K.swipeStart, v.name)
    suspend fun setSwipeEnd(v: SwipeAction) = set(K.swipeEnd, v.name)
    suspend fun setDefaultSubId(v: Int) = set(K.defaultSubId, v)
    suspend fun setSendDelayMs(v: Long) = set(K.sendDelayMs, v)
    suspend fun setAutoReplyEnabled(v: Boolean) = set(K.arEnabled, v)
    suspend fun setAutoReplyMode(v: AutoReplyMode) = set(K.arMode, v.name)
    suspend fun setAutoReplyText(v: String) = set(K.arText, v)
    suspend fun setAutoReplyContactsOnly(v: Boolean) = set(K.arContactsOnly, v)
    suspend fun setAutoReplyCooldown(v: Int) = set(K.arCooldown, v)
    suspend fun setAutoReplyWindow(v: Boolean) = set(K.arWindow, v)
    suspend fun setAutoReplyFrom(v: Int) = set(K.arFrom, v)
    suspend fun setAutoReplyTo(v: Int) = set(K.arTo, v)
    suspend fun setBackupFrequency(v: BackupFrequency) = set(K.backupFrequency, v.name)
    suspend fun setBackupTreeUri(v: String?) {
        context.dataStore.edit { if (v == null) it.remove(K.backupTreeUri) else it[K.backupTreeUri] = v }
    }
    suspend fun setLastBackupAt(v: Long) = set(K.lastBackupAt, v)
    suspend fun setOnboardingDone(v: Boolean) = set(K.onboardingDone, v)
    suspend fun setLastBatchId(v: Long) = set(K.lastBatchId, v)
    suspend fun setBulkIntroShown(v: Boolean) = set(K.bulkIntroShown, v)
    suspend fun setAutoRetry(v: Boolean) = set(K.autoRetry, v)
    suspend fun setRespectLimit(v: Boolean) = set(K.respectLimit, v)
    suspend fun setReactionsAsSms(v: Boolean) = set(K.reactionsAsSms, v)
    suspend fun setExternalSeenAt(v: Long) = set(K.externalSeenAt, v)
    suspend fun setDevMode(v: Boolean) = set(K.devMode, v)
    suspend fun setGatewayEnabled(v: Boolean) = set(K.gatewayEnabled, v)
    suspend fun setGatewayPort(v: Int) = set(K.gatewayPort, v)
    suspend fun setGatewayKey(v: String) = set(K.gatewayKey, v)
    suspend fun setGatewayDedupe(v: Boolean) = set(K.gatewayDedupe, v)
    suspend fun setGatewayBatchId(v: Long) = set(K.gatewayBatchId, v)
    suspend fun setLockEnabled(v: Boolean) = set(K.lockEnabled, v)
    suspend fun setLockMethod(v: LockMethod) = set(K.lockMethod, v.name)
    suspend fun setLockAfter(v: Int) = set(K.lockAfter, v)
    suspend fun setSeenVersion(v: String) = set(K.seenVersion, v)
    suspend fun setClassVersion(v: Int) = set(K.classVersion, v)
    suspend fun setSnapshotChecked(v: Boolean) = set(K.snapshotChecked, v)

    /** Applies settings restored from a backup (everything the user chose, nothing technical). */
    suspend fun restore(s: AppSettings) {
        context.dataStore.edit {
            it[K.themeMode] = s.themeMode.name
            it[K.dynamicColor] = s.dynamicColor
            it[K.pillNav] = s.pillNav
            it[K.fontScale] = s.fontScale
            it[K.datePattern] = s.datePattern
            it[K.use24h] = s.use24h
            it[K.deliveryReports] = s.deliveryReports
            it[K.showCounter] = s.showCounter
            it[K.mmsEnabled] = s.mmsEnabled
            it[K.spamFilter] = s.spamFilter
            it[K.recycleBin] = s.recycleBin
            it[K.swipeStart] = s.swipeStart.name
            it[K.swipeEnd] = s.swipeEnd.name
            it[K.defaultSubId] = s.defaultSubId
            it[K.sendDelayMs] = s.sendDelayMs
            it[K.arEnabled] = s.autoReplyEnabled
            it[K.arMode] = s.autoReplyMode.name
            it[K.arText] = s.autoReplyText
            it[K.arContactsOnly] = s.autoReplyContactsOnly
            it[K.arCooldown] = s.autoReplyCooldownMin
            it[K.arWindow] = s.autoReplyWindow
            it[K.arFrom] = s.autoReplyFromMin
            it[K.arTo] = s.autoReplyToMin
            it[K.backupFrequency] = s.backupFrequency.name
            it[K.autoRetry] = s.autoRetry
            it[K.respectLimit] = s.respectLimit
            it[K.reactionsAsSms] = s.reactionsAsSms
        }
    }
}
