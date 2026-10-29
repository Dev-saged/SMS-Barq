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
enum class SwipeAction { NONE, ARCHIVE, DELETE, READ, PIN, MUTE }
enum class AutoReplyMode { FIXED, SMART }
enum class BackupFrequency { OFF, DAILY, WEEKLY }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val fontScale: Float = 1f,
    val datePattern: String = "dd/MM/yyyy",
    val use24h: Boolean = true,
    val deliveryReports: Boolean = false,
    val showCounter: Boolean = true,
    val mmsEnabled: Boolean = false,
    val spamFilter: Boolean = true,
    val recycleBin: Boolean = true,
    val swipeStart: SwipeAction = SwipeAction.ARCHIVE,
    val swipeEnd: SwipeAction = SwipeAction.DELETE,
    val defaultSubId: Int = -1,
    val sendDelayMs: Long = 250,
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
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object K {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
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
    }

    private inline fun <reified T : Enum<T>> Preferences.enumOf(key: Preferences.Key<String>, default: T): T =
        this[key]?.let { name -> runCatching { enumValueOf<T>(name) }.getOrNull() } ?: default

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            themeMode = p.enumOf(K.themeMode, d.themeMode),
            dynamicColor = p[K.dynamicColor] ?: d.dynamicColor,
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
            autoReplyText = p[K.arText] ?: d.autoReplyText,
            autoReplyContactsOnly = p[K.arContactsOnly] ?: d.autoReplyContactsOnly,
            autoReplyCooldownMin = p[K.arCooldown] ?: d.autoReplyCooldownMin,
            autoReplyWindow = p[K.arWindow] ?: d.autoReplyWindow,
            autoReplyFromMin = p[K.arFrom] ?: d.autoReplyFromMin,
            autoReplyToMin = p[K.arTo] ?: d.autoReplyToMin,
            backupFrequency = p.enumOf(K.backupFrequency, d.backupFrequency),
            backupTreeUri = p[K.backupTreeUri],
            lastBackupAt = p[K.lastBackupAt] ?: d.lastBackupAt,
            onboardingDone = p[K.onboardingDone] ?: d.onboardingDone,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setThemeMode(v: ThemeMode) = set(K.themeMode, v.name)
    suspend fun setDynamicColor(v: Boolean) = set(K.dynamicColor, v)
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
}
