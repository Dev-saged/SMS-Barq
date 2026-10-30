package dev.saged.barq.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.saged.barq.data.db.Box
import dev.saged.barq.di.barq
import dev.saged.barq.util.goAsync

/** Fired by AlarmManager when a scheduled message is due. */
class ScheduledAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.barq.sendQueue().kick()
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "message_id"
    }
}

/** Restores alarms after reboot/update and recovers messages that were mid-send. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entry = context.barq
        goAsync {
            entry.scheduler().rescheduleAll()
            // Anything left in OUTBOX never got a radio result: mark failed so the user can resend.
            val dao = entry.messageDao()
            dao.staleOutbox(System.currentTimeMillis() - 60_000).forEach { dao.setBox(it.id, Box.FAILED, 0) }
            entry.maintenance().ensureScheduled()
            entry.sendQueue().kick()
            // The local gateway comes back after a restart when it was left on.
            if (entry.settings().current().gatewayEnabled) dev.saged.barq.sms.gateway.GatewayService.start(context)
        }
    }
}
