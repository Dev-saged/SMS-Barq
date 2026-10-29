package dev.saged.barq.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.saged.barq.data.db.Attachment
import dev.saged.barq.di.barq
import dev.saged.barq.util.goAsync
import dev.saged.barq.util.smsManagerFor
import java.io.File

/**
 * Optional MMS receiving. The default SMS app must accept WAP push; when the user keeps MMS off
 * we only show a hint notification. When on, the system MMS service downloads the message and
 * Barq stores its text and pictures. Outgoing messages are always plain SMS.
 */
class MmsWapPushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        val subId = intent.getIntExtra("subscription", -1)
        val entry = context.barq
        goAsync {
            val notification = MmsPdu.parseNotification(data) ?: return@goAsync
            if (!entry.settings().current().mmsEnabled) {
                entry.notifier().notifyMmsDisabled(notification.from ?: "")
                return@goAsync
            }
            MmsTransport.download(context, notification, subId)
        }
    }
}

class MmsDownloadedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra(MmsTransport.EXTRA_PATH) ?: return
        val subId = intent.getIntExtra(MmsTransport.EXTRA_SUB_ID, -1)
        val transactionId = intent.getStringExtra(MmsTransport.EXTRA_TRANSACTION_ID).orEmpty()
        val version = intent.getIntExtra(MmsTransport.EXTRA_VERSION, 0x12)
        val fallbackFrom = intent.getStringExtra(MmsTransport.EXTRA_FROM).orEmpty()
        val ok = resultCode == Activity.RESULT_OK
        val entry = context.barq
        goAsync {
            val file = File(path)
            val bytes = if (ok && file.exists()) runCatching { file.readBytes() }.getOrNull() else null
            file.delete()
            val conf = bytes?.let { MmsPdu.parseRetrieveConf(it) } ?: return@goAsync
            val from = conf.from?.takeIf { it.isNotBlank() } ?: fallbackFrom
            if (from.isBlank()) return@goAsync

            val text = conf.parts.filter { it.isText }.joinToString("\n") { it.text() }.trim()
            val dir = entry.incoming().attachmentDir()
            val attachments = conf.parts
                .filter { !it.isText && !it.contentType.contains("smil") && it.data.isNotEmpty() }
                .mapIndexed { i, part ->
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(part.contentType) ?: "bin"
                    val out = File(dir, "mms_${System.currentTimeMillis()}_$i.$ext")
                    out.writeBytes(part.data)
                    Attachment(part.contentType, out.absolutePath, part.name)
                }
            entry.incomingProcessor().onMms(from, text, conf.subject, attachments, conf.date, subId)
            if (transactionId.isNotEmpty()) MmsTransport.acknowledge(context, transactionId, version, subId)
        }
    }
}

object MmsTransport {
    const val EXTRA_PATH = "path"
    const val EXTRA_SUB_ID = "sub_id"
    const val EXTRA_TRANSACTION_ID = "transaction_id"
    const val EXTRA_VERSION = "version"
    const val EXTRA_FROM = "from"

    private fun authority(context: Context) = "${context.packageName}.files"

    private fun pduFile(context: Context, prefix: String): File {
        val dir = File(context.cacheDir, "mms").apply { mkdirs() }
        return File(dir, "${prefix}_${System.nanoTime()}.pdu")
    }

    private fun shareWithTelephony(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        listOf("com.android.phone", "com.android.mms.service").forEach { pkg ->
            runCatching { context.grantUriPermission(pkg, uri, flags) }
        }
    }

    fun download(context: Context, n: MmsPdu.Notification, subId: Int) {
        val file = pduFile(context, "in").apply { createNewFile() }
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        shareWithTelephony(context, uri)
        val callback = Intent(context, MmsDownloadedReceiver::class.java)
            .setData(Uri.parse("barq://mms/${file.name}"))
            .putExtra(EXTRA_PATH, file.absolutePath)
            .putExtra(EXTRA_SUB_ID, subId)
            .putExtra(EXTRA_TRANSACTION_ID, n.transactionId)
            .putExtra(EXTRA_VERSION, n.version)
            .putExtra(EXTRA_FROM, n.from)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, callback, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
        runCatching {
            smsManagerFor(context, subId).downloadMultimediaMessage(context, n.contentLocation, uri, null, pi)
        }.onFailure { file.delete() }
    }

    /** Sends m-notifyresp.ind so the carrier stops re-sending the notification. */
    fun acknowledge(context: Context, transactionId: String, version: Int, subId: Int) {
        runCatching {
            val file = pduFile(context, "ack")
            file.writeBytes(MmsPdu.encodeNotifyResp(transactionId, version))
            val uri = FileProvider.getUriForFile(context, authority(context), file)
            shareWithTelephony(context, uri)
            smsManagerFor(context, subId).sendMultimediaMessage(context, uri, null, null, null)
        }
    }
}
