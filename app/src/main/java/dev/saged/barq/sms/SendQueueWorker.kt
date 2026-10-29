package dev.saged.barq.sms

import android.content.Context
import android.telephony.SmsManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.saged.barq.data.MessageRepository
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.settings.SettingsRepository
import kotlinx.coroutines.delay

/**
 * Drains the outgoing queue sequentially with a small pause between messages so the modem is
 * never flooded. Runs in the background: the UI stays smooth even with hundreds of messages.
 */
@HiltWorker
class SendQueueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val messages: MessageDao,
    private val repository: MessageRepository,
    private val sender: SmsSender,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        val s = settings.current()
        while (true) {
            val batch = messages.dueQueued(System.currentTimeMillis(), BATCH)
            if (batch.isEmpty()) break
            for (queued in batch) {
                if (isStopped) return Result.success()
                val outbox = repository.markOutbox(queued)
                runCatching { sender.send(outbox, s.deliveryReports) }
                    .onFailure { repository.onSendResult(outbox.id, false, SmsManager.RESULT_ERROR_GENERIC_FAILURE) }
                if (s.sendDelayMs > 0) delay(s.sendDelayMs)
            }
            // Stay well below WorkManager's 10-minute limit; a fresh worker continues the queue.
            if (System.currentTimeMillis() - started > MAX_RUN_MS) {
                SendQueue(applicationContext).kick()
                break
            }
        }
        return Result.success()
    }

    private companion object {
        const val BATCH = 50
        const val MAX_RUN_MS = 8 * 60 * 1000L
    }
}
