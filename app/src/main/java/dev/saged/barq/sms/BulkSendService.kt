package dev.saged.barq.sms

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.saged.barq.data.db.BatchCounts
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.notifications.Notifier
import dev.saged.barq.util.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sends a bulk message in the foreground, with a live progress notification (sent / failed /
 * remaining) and Pause / Cancel buttons. Android never stops it halfway, however long the list.
 *
 * Android 15+ allows this kind of service 6 hours a day in the background. When that time is up
 * Barq pauses the batch itself (instead of being shut down), asks to finish it from a
 * notification, and hands other waiting messages to the background queue.
 */
@AndroidEntryPoint
class BulkSendService : Service() {

    @Inject lateinit var drainer: SendDrainer
    @Inject lateinit var state: BulkState
    @Inject lateinit var messages: MessageDao
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var notifier: Notifier
    @Inject @field:dev.saged.barq.di.AppScope lateinit var appScope: CoroutineScope

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var runningBatch = 0L
    @Volatile private var timedOut = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = runCatching {
            ServiceCompat.startForeground(
                this, Notifier.BULK_ID, notifier.bulkProgress(0, BatchCounts(), preparing = true, paused = false),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.onFailure { CrashLog.warn("bulk-start", it) }.isSuccess
        if (!ok) {
            SendQueue(applicationContext).kick()
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) job = scope.launch { run() }
        return START_NOT_STICKY
    }

    private class Progress(
        val counts: BatchCounts,
        val preparing: Boolean,
        val paused: Boolean,
        val waitingLimit: Boolean,
        val stalled: Boolean,
    )

    private suspend fun run() {
        val batchId = settings.current().lastBatchId
        runningBatch = batchId
        // The screen may turn off during a long send: keep the CPU awake until it is finished.
        // The lock has a time limit as a safety net, so it is renewed while the send goes on.
        wakeLock = runCatching {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Barq:bulk-send")
                .apply { setReferenceCounted(false); acquire(MAX_WAKE_MS) }
        }.getOrNull()
        val keepAwake = scope.launch {
            while (true) {
                delay(RENEW_WAKE_MS)
                runCatching { wakeLock?.acquire(MAX_WAKE_MS) }
            }
        }
        val progress = scope.launch {
            combine(
                messages.observeBatch(batchId), state.preparing, state.paused, state.waitingLimitUntil, state.stalled,
            ) { counts, preparing, paused, waitUntil, stalled ->
                Progress(counts, preparing, paused == batchId && batchId != 0L, waitUntil > 0L, stalled)
            }.conflate().collect { p ->
                notifier.updateBulkProgress(batchId, p.counts, p.preparing, p.paused, p.waitingLimit, p.stalled)
                delay(400)
            }
        }
        try {
            state.preparing.first { !it }
            while (true) {
                val result = drainer.drain()
                if (result == SendDrainer.Result.BUSY) {
                    delay(500) // a worker is finishing a message; take over right after
                    continue
                }
                val paused = state.paused.value
                if (paused != 0L && messages.batchCounts(paused).pending > 0) {
                    state.paused.first { it != paused } // keep the notification until resumed or cancelled
                    continue
                }
                if (state.preparing.value) {
                    state.preparing.first { !it }
                    continue
                }
                // Some messages wait for an automatic retry: stay (and keep the progress) until then.
                val now = System.currentTimeMillis()
                val next = messages.nextRetryAt(now)
                if (batchId != 0L && next != null && messages.batchCounts(batchId).retrying > 0) {
                    delay((next - now).coerceIn(1_000L, MAX_RETRY_WAIT_MS))
                    continue
                }
                break
            }
        } catch (t: Throwable) {
            if (t !is kotlinx.coroutines.CancellationException) CrashLog.warn("bulk-run", t)
        } finally {
            progress.cancel()
            keepAwake.cancel()
            if (batchId != 0L && !timedOut) {
                val counts = runCatching { messages.batchCounts(batchId) }.getOrNull()
                if (counts != null && counts.total > 0) notifier.notifyBulkFinished(batchId, counts)
            }
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Android 15+: the 6 hours are used up. Stop within seconds, keeping the batch paused. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        val batchId = runningBatch
        if (batchId != 0L) state.pause(batchId)
        job?.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        appScope.launch {
            runCatching {
                val pending = if (batchId != 0L) messages.batchCounts(batchId).pending else 0
                if (pending > 0) notifier.notifyBulkTimeLimit(batchId, pending)
                // Single messages and the gateway keep going through the background queue.
                SendQueue(applicationContext).kick()
            }.onFailure { CrashLog.warn("bulk-timeout", it) }
        }
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MAX_WAKE_MS = 3 * 60 * 60 * 1000L
        private const val RENEW_WAKE_MS = 2 * 60 * 60 * 1000L
        private const val MAX_RETRY_WAIT_MS = 4 * 60 * 1000L

        /** Starts (or nudges) the service. Returns false when Android does not allow it right now. */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, BulkSendService::class.java))
        }.onFailure { CrashLog.warn("bulk-start", it) }.isSuccess
    }
}
