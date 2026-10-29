package dev.saged.barq.sms

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
 */
@AndroidEntryPoint
class BulkSendService : Service() {

    @Inject lateinit var drainer: SendDrainer
    @Inject lateinit var state: BulkState
    @Inject lateinit var messages: MessageDao
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var notifier: Notifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

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

    private suspend fun run() {
        val batchId = settings.current().lastBatchId
        val progress = scope.launch {
            combine(messages.observeBatch(batchId), state.preparing, state.paused) { counts, preparing, paused ->
                Triple(counts, preparing, paused == batchId && batchId != 0L)
            }.conflate().collect { (counts, preparing, paused) ->
                notifier.updateBulkProgress(batchId, counts, preparing, paused)
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
                break
            }
        } catch (t: Throwable) {
            if (t !is kotlinx.coroutines.CancellationException) CrashLog.warn("bulk-run", t)
        } finally {
            progress.cancel()
            if (batchId != 0L) {
                val counts = runCatching { messages.batchCounts(batchId) }.getOrNull()
                if (counts != null && counts.total > 0) notifier.notifyBulkFinished(batchId, counts)
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Starts (or nudges) the service. Returns false when Android does not allow it right now. */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, BulkSendService::class.java))
        }.onFailure { CrashLog.warn("bulk-start", it) }.isSuccess
    }
}
