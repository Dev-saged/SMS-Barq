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
        val entry = context.barq
        when (intent.action) {
            ACTION_COPY_OTP -> {
                val code = intent.getStringExtra(EXTRA_VALUE) ?: return
                copyToClipboard(context, code)
                goAsync {
                    entry.repository().markRead(listOf(threadId))
                    entry.notifier().cancel(threadId)
                }
            }
            ACTION_MARK_READ -> goAsync {
                entry.repository().markRead(listOf(threadId))
                entry.notifier().cancel(threadId)
            }
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                if (text.isNullOrBlank() || threadId < 0) return
                goAsync {
                    val repo = entry.repository()
                    repo.markRead(listOf(threadId))
                    repo.queueToThread(threadId, text, -1)
                    entry.notifier().cancel(threadId)
                }
            }
            ACTION_DISMISS -> Unit
        }
    }

    companion object {
        const val ACTION_COPY_OTP = "dev.saged.barq.COPY_OTP"
        const val ACTION_MARK_READ = "dev.saged.barq.MARK_READ"
        const val ACTION_REPLY = "dev.saged.barq.REPLY"
        const val ACTION_DISMISS = "dev.saged.barq.DISMISS"
        const val EXTRA_THREAD_ID = "thread_id"
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
