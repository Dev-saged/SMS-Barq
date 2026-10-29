package dev.saged.barq.sms

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.saged.barq.notifications.Notifier

/**
 * Sends queued messages in the background through [SendDrainer]. Runs as expedited work so a
 * reply typed in a notification leaves immediately, not minutes later.
 */
@HiltWorker
class SendQueueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val drainer: SendDrainer,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = drainer.drain(maxMillis = MAX_RUN_MS, stopWhen = { isStopped })
        // Stay well below WorkManager's limit; a fresh worker continues the queue.
        if (result == SendDrainer.Result.TIME_UP) SendQueue(applicationContext).kick()
        return Result.success()
    }

    /** Needed for expedited work on Android 11 and older (shown only while sending). */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.sendingNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.SENDING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.SENDING_ID, notification)
        }
    }

    private companion object {
        const val MAX_RUN_MS = 8 * 60 * 1000L
    }
}
