package dev.saged.barq.notifications

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.RemoteInput
import dev.saged.barq.R
import dev.saged.barq.di.barq
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.util.goAsync

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1)
        val batchId = intent.getLongExtra(EXTRA_BATCH_ID, 0)
        val entry = context.barq
        when (intent.action) {
            ACTION_COPY_OTP -> {
                val code = intent.getStringExtra(EXTRA_VALUE) ?: return
                copyToClipboard(context, code)
                goAsync {
                    entry.conversations().markRead(listOf(threadId))
                    entry.notifier().cancel(threadId)
                }
            }
            ACTION_MARK_READ -> goAsync {
                entry.conversations().markRead(listOf(threadId))
                entry.notifier().cancel(threadId)
            }
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                if (text.isNullOrBlank() || threadId < 0) return
                goAsync {
                    entry.conversations().markRead(listOf(threadId))
                    // subId = null → the conversation's SIM, then Barq's default SIM.
                    entry.engine().sendToThread(threadId, text, subId = null)
                    entry.notifier().cancel(threadId)
                }
            }
            ACTION_BATCH_PAUSE -> if (batchId != 0L) entry.engine().pauseBatch(batchId)
            ACTION_BATCH_RESUME -> {
                entry.notifier().cancelGatewayStopped()
                entry.engine().resumeBatch()
            }
            ACTION_BATCH_CANCEL -> {
                entry.notifier().cancelGatewayStopped()
                if (batchId != 0L) goAsync { entry.engine().cancelBatch(batchId) }
            }
            ACTION_BATCH_RESEND -> if (batchId != 0L) goAsync {
                entry.notifier().cancelBulkFinished()
                entry.engine().resendFailed(batchId)
            }
            ACTION_DISMISS -> Unit
        }
    }

    companion object {
        const val ACTION_COPY_OTP = "dev.saged.barq.COPY_OTP"
        const val ACTION_MARK_READ = "dev.saged.barq.MARK_READ"
        const val ACTION_REPLY = "dev.saged.barq.REPLY"
        const val ACTION_DISMISS = "dev.saged.barq.DISMISS"
        const val ACTION_BATCH_PAUSE = "dev.saged.barq.BATCH_PAUSE"
        const val ACTION_BATCH_RESUME = "dev.saged.barq.BATCH_RESUME"
        const val ACTION_BATCH_CANCEL = "dev.saged.barq.BATCH_CANCEL"
        const val ACTION_BATCH_RESEND = "dev.saged.barq.BATCH_RESEND"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_BATCH_ID = "batch_id"
        const val EXTRA_VALUE = "value"
        const val KEY_REPLY = "reply_text"

        fun copyToClipboard(context: Context, code: String) {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("OTP", PhoneUtils.normalizeDigits(code)))
            // Android 13+ shows its own "Copied" confirmation.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, context.getString(R.string.code_copied), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
