package dev.saged.barq.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.util.smsManagerFor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a message to the radio. Always plain SMS: long texts are split into standard
 * multipart SMS — they are never converted to MMS, whatever their length.
 */
@Singleton
class SmsSender @Inject constructor(@ApplicationContext private val context: Context) {

    fun send(message: MessageEntity, deliveryReports: Boolean) {
        val manager = smsManagerFor(context, message.subId)
        val parts = manager.divideMessage(message.body)
        val count = parts.size.coerceAtLeast(1)
        val sent = ArrayList<PendingIntent>(count)
        val delivered = ArrayList<PendingIntent?>(count)
        for (i in 0 until count) {
            sent += statusIntent(SmsStatusReceiver.ACTION_SENT, message.id, i, count)
            // One delivery report per message (on the last part) is enough and saves callbacks.
            delivered += if (deliveryReports && i == count - 1) {
                statusIntent(SmsStatusReceiver.ACTION_DELIVERED, message.id, i, count)
            } else null
        }
        if (count == 1) {
            manager.sendTextMessage(message.address, null, message.body, sent[0], delivered[0])
        } else {
            manager.sendMultipartTextMessage(message.address, null, parts, sent, ArrayList(delivered))
        }
    }

    private fun statusIntent(action: String, messageId: Long, part: Int, parts: Int): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("barq://status/$action/$messageId/$part"))
            .putExtra(SmsStatusReceiver.EXTRA_MESSAGE_ID, messageId)
            .putExtra(SmsStatusReceiver.EXTRA_PART, part)
            .putExtra(SmsStatusReceiver.EXTRA_PARTS, parts)
        // Mutable so the radio can attach the delivery PDU / error code extras.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }
}
