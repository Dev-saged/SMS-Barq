package dev.saged.barq.data

import android.database.ContentObserver
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.di.AppScope
import dev.saged.barq.util.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the system's SMS store while Barq's process is alive. When another app sends (a
 * gateway driven by a bot, for example), Android writes each message there: Barq imports it at
 * once into the right conversation and follows it until it is sent, failed or delivered.
 *
 * Changes are grouped (a burst of 500 writes = a few passes), so it stays light during big sends.
 */
@Singleton
class ProviderWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: SyncManager,
    @AppScope private val scope: CoroutineScope,
) {
    private val signal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var registered = false
    @Volatile private var looping = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            signal.trySend(Unit)
        }
    }

    /** Safe to call many times (app start, permission granted). */
    fun start() {
        if (!registered) {
            registered = runCatching {
                context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            }.onFailure { CrashLog.warn("provider-watch", it) }.isSuccess
        }
        if (!looping) {
            looping = true
            scope.launch {
                for (ignored in signal) {
                    delay(GROUP_MS)
                    runCatching {
                        sync.sync()
                        sync.reconcile()
                    }.onFailure { CrashLog.warn("provider-sync", it) }
                }
            }
        }
        // Catch up with anything that happened while Barq was not running.
        signal.trySend(Unit)
    }

    private companion object {
        const val GROUP_MS = 700L
    }
}
