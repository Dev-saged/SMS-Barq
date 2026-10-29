package dev.saged.barq.sms

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outgoing messages are rows with box = QUEUED. Kicking the queue appends a worker that drains
 * them one by one, so sending 500 messages never blocks the UI and survives the app closing.
 */
@Singleton
class SendQueue @Inject constructor(@ApplicationContext private val context: Context) {

    fun kick() {
        val request = OneTimeWorkRequestBuilder<SendQueueWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val WORK_NAME = "barq-send-queue"
    }
}
