package dev.saged.barq.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import dev.saged.barq.di.barq
import dev.saged.barq.util.CrashLog
import dev.saged.barq.util.goAsync

/** Receives "sent" and "delivered" callbacks from the radio. */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1)
        if (messageId < 0) return
        val result = resultCode
        val entry = context.barq
        goAsync {
            val outgoing = entry.outgoing()
            when (intent.action) {
                ACTION_SENT -> {
                    val part = intent.getIntExtra(EXTRA_PART, 0)
                    val parts = intent.getIntExtra(EXTRA_PARTS, 1)
                    val ok = result == Activity.RESULT_OK
                    // Only the last part confirms success; any failing part fails the message.
                    if (!ok || part == parts - 1) {
                        // A temporary failure is retried by itself and is not reported as failed.
                        val failedForGood = outgoing.onSendResult(messageId, ok, if (ok) 0 else result)
                        // Bulk sends report failures in their own summary, not one by one.
                        if (failedForGood && entry.messageDao().get(messageId)?.batchId == 0L) {
                            entry.notifier().notifySendFailed(messageId)
                        }
                    }
                }
                ACTION_DELIVERED -> {
                    val pdu = intent.getByteArrayExtra("pdu")
                    val format = intent.getStringExtra("format")
                    val status = runCatching { SmsMessage.createFromPdu(pdu, format)?.status }.getOrNull()
                    // TP-Status: 0x00..0x1F delivered, 0x20..0x3F still trying, 0x40+ failed.
                    // A report that cannot be read is not taken as "delivered" (it used to be).
                    when {
                        status == null -> CrashLog.warn(
                            "delivery-report",
                            IllegalStateException("Unreadable delivery report (format=$format, pdu=${pdu?.size ?: -1} bytes)"),
                        )
                        status < 0x20 -> outgoing.onDelivery(messageId, true)
                        status >= 0x40 -> outgoing.onDelivery(messageId, false)
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_SENT = "dev.saged.barq.SMS_SENT"
        const val ACTION_DELIVERED = "dev.saged.barq.SMS_DELIVERED"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_PART = "part"
        const val EXTRA_PARTS = "parts"
    }
}
