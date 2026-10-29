package dev.saged.barq.sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.data.db.MessageDao
import javax.inject.Inject
import javax.inject.Singleton

/** Wakes the send queue at the exact time a scheduled message is due. */
@Singleton
class ScheduleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messages: MessageDao,
) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    private fun pending(messageId: Long, create: Boolean): PendingIntent? {
        val intent = Intent(context, ScheduledAlarmReceiver::class.java)
            .setData(Uri.parse("barq://scheduled/$messageId"))
            .putExtra(ScheduledAlarmReceiver.EXTRA_MESSAGE_ID, messageId)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun schedule(messageId: Long, at: Long) {
        val pi = pending(messageId, create = true) ?: return
        if (canScheduleExact()) {
            AlarmManagerCompat.setExactAndAllowWhileIdle(alarms, AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // Without the exact-alarm permission Android may delay this by a few minutes.
            AlarmManagerCompat.setAndAllowWhileIdle(alarms, AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(messageId: Long) {
        pending(messageId, create = false)?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }

    suspend fun cancelForThread(threadId: Long) {
        messages.futureScheduled(0).filter { it.threadId == threadId }.forEach { cancel(it.id) }
    }

    /** After reboot or app update alarms are gone — put them back. */
    suspend fun rescheduleAll() {
        messages.futureScheduled(System.currentTimeMillis()).forEach { schedule(it.id, it.scheduledAt) }
    }
}
