package dev.saged.barq.sms

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outgoing messages are rows with box = QUEUED. Kicking the queue starts an expedited worker that
 * sends them right away, even when Barq is closed (a reply from a notification, an auto-reply).
 * Bulk sends use [BulkSendService] instead; both go through [SendDrainer].
 */
@Singleton
class SendQueue @Inject constructor(@ApplicationContext private val context: Context) {

    fun kick() {
        val request = OneTimeWorkRequestBuilder<SendQueueWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val WORK_NAME = "barq-send-queue"
    }
}
