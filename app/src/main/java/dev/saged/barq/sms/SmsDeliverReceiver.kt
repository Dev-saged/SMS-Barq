package dev.saged.barq.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dev.saged.barq.di.barq
import dev.saged.barq.util.goAsync

/** SMS_DELIVER: only the default SMS app gets this broadcast, and it must store the message. */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()
        if (parts.isNullOrEmpty()) return
        val subId = intent.getIntExtra(
            "subscription",
            intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1)
        )
        // Long SMS arrive as several PDUs in one intent — join them back into one message.
        val grouped = parts.filterNotNull().groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }
        val processor = context.barq.incomingProcessor()
        goAsync {
            grouped.forEach { (address, pdus) ->
                if (address.isBlank()) return@forEach
                val body = pdus.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
                val sentAt = pdus.first().timestampMillis
                processor.onSms(address, body, sentAt, subId)
            }
        }
    }
}
