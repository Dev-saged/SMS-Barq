package dev.saged.barq.sms

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's protection against apps that send too many SMS: by default an app may send about
 * 30 messages per minute; above that Android stops and asks the user. Both numbers live in two
 * global settings that anyone can read. Changing them needs WRITE_SECURE_SETTINGS, which only
 * the phone's owner can give with one adb command (wireless debugging works from the phone
 * itself). Once given, Barq raises or restores the limit with a button.
 *
 * The setting is global, so raising it also helps other apps (a gateway driven by a bot…).
 */
@Singleton
class SendLimit @Inject constructor(@ApplicationContext private val context: Context) {

    data class Limit(
        /** Messages allowed in [intervalMs]. */
        val maxCount: Int,
        val intervalMs: Long,
        /** True when nobody changed it (Android's default is in use). */
        val isDefault: Boolean,
    ) {
        /** Messages per minute, for display. */
        val perMinute: Int get() = if (intervalMs <= 0) maxCount else (maxCount * 60_000L / intervalMs).toInt()
        val raised: Boolean get() = !isDefault && maxCount >= RAISED_COUNT / 2
    }

    fun current(): Limit {
        val resolver = context.contentResolver
        val count = runCatching { Settings.Global.getString(resolver, KEY_MAX_COUNT)?.toIntOrNull() }.getOrNull()
        val interval = runCatching { Settings.Global.getString(resolver, KEY_INTERVAL)?.toLongOrNull() }.getOrNull()
        return Limit(
            maxCount = count?.takeIf { it > 0 } ?: DEFAULT_COUNT,
            intervalMs = interval?.takeIf { it > 0 } ?: DEFAULT_INTERVAL_MS,
            isDefault = count == null && interval == null,
        )
    }

    /** True once the user gave Barq the permission (with adb). */
    fun canChange(): Boolean =
        context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Raises the limit high enough that big sends never stop. Returns false without permission. */
    fun raise(): Boolean = runCatching {
        val resolver = context.contentResolver
        Settings.Global.putString(resolver, KEY_MAX_COUNT, RAISED_COUNT.toString()) &&
            Settings.Global.putString(resolver, KEY_INTERVAL, DEFAULT_INTERVAL_MS.toString())
    }.getOrDefault(false)

    /** Back to Android's own default. */
    fun restore(): Boolean = runCatching {
        val resolver = context.contentResolver
        Settings.Global.putString(resolver, KEY_MAX_COUNT, null)
        Settings.Global.putString(resolver, KEY_INTERVAL, null)
        true
    }.getOrDefault(false)

    companion object {
        const val KEY_MAX_COUNT = "sms_outgoing_check_max_count"
        const val KEY_INTERVAL = "sms_outgoing_check_interval_ms"
        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
        /** Android's own values (AOSP): 30 messages per minute. */
        const val DEFAULT_COUNT = 30
        const val DEFAULT_INTERVAL_MS = 60_000L
        const val RAISED_COUNT = 10_000

        /** The command the user runs once (from a computer or the phone's own wireless debugging). */
        fun grantCommand(packageName: String) = "adb shell pm grant $packageName $WRITE_SECURE_SETTINGS"

        /** Alternative without giving Barq the permission: raise the limit directly. */
        fun directCommand() = "adb shell settings put global $KEY_MAX_COUNT $RAISED_COUNT"
    }
}
