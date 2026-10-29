package dev.saged.barq.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.MainActivity
import dev.saged.barq.R
import dev.saged.barq.data.db.ConversationDao
import dev.saged.barq.data.db.MessageDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversations: ConversationDao,
    private val messages: MessageDao,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_OTP, context.getString(R.string.channel_otp), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_otp_desc)
                },
                NotificationChannel(CH_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openThread(threadId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("barq://thread/$threadId"))
            .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, threadId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun action(action: String, threadId: Long, extra: String? = null, mutable: Boolean = false): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("barq://action/$action/$threadId"))
            .putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, threadId)
        if (extra != null) intent.putExtra(NotificationActionReceiver.EXTRA_VALUE, extra)
        val mutability = if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutability
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /** One grouped notification per conversation, with Copy-code, Reply and Mark-read actions. */
    suspend fun notifyConversation(threadId: Long) {
        if (!canPost()) return
        val conv = conversations.get(threadId) ?: return
        if (conv.muted) return
        val unread = messages.unread(threadId)
        if (unread.isEmpty()) {
            cancel(threadId)
            return
        }
        val latest = unread.last()
        val otp = latest.otp
        val me = Person.Builder().setName(context.getString(R.string.me)).build()
        val sender = Person.Builder().setName(conv.displayName).setKey(conv.addresses).build()
        val style = NotificationCompat.MessagingStyle(me)
        unread.takeLast(6).forEach { style.addMessage(it.body.ifBlank { "[وسائط]" }, it.date, sender) }

        val builder = NotificationCompat.Builder(context, if (otp != null) CH_OTP else CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_barq)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setStyle(style)
            .setContentTitle(conv.displayName)
            .setContentText(latest.body)
            .setWhen(latest.date)
            .setShowWhen(true)
            .setNumber(unread.size)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openThread(threadId))
            .setDeleteIntent(action(NotificationActionReceiver.ACTION_DISMISS, threadId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)

        if (otp != null) {
            builder.addAction(
                R.drawable.ic_copy,
                context.getString(R.string.copy_code_value, otp),
                action(NotificationActionReceiver.ACTION_COPY_OTP, threadId, otp)
            )
        }
        if (dev.saged.barq.domain.PhoneUtils.isReplyable(conv.addresses)) {
            val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY)
                .setLabel(context.getString(R.string.reply))
                .build()
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_reply, context.getString(R.string.reply),
                    action(NotificationActionReceiver.ACTION_REPLY, threadId, mutable = true)
                ).addRemoteInput(remoteInput)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build()
            )
        }
        builder.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_done, context.getString(R.string.mark_read),
                action(NotificationActionReceiver.ACTION_MARK_READ, threadId)
            ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build()
        )
        runCatching { manager.notify(threadId.toInt(), builder.build()) }
    }

    suspend fun notifySendFailed(messageId: Long) {
        if (!canPost()) return
        val msg = messages.get(messageId) ?: return
        val conv = conversations.get(msg.threadId)
        val n = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_barq)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(context.getString(R.string.send_failed_title))
            .setContentText(context.getString(R.string.send_failed_text, conv?.displayName ?: msg.address))
            .setContentIntent(openThread(msg.threadId))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(FAILED_ID_BASE + msg.threadId.toInt(), n) }
    }

    fun notifyMmsDisabled(from: String) {
        if (!canPost()) return
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val n = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_barq)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(context.getString(R.string.mms_disabled_title))
            .setContentText(context.getString(R.string.mms_disabled_text, from))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.mms_disabled_text, from)))
            .setContentIntent(PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(MMS_DISABLED_ID, n) }
    }

    fun cancel(threadId: Long) = manager.cancel(threadId.toInt())

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_OTP = "otp"
        const val CH_STATUS = "status"
        private const val FAILED_ID_BASE = 1_000_000
        private const val MMS_DISABLED_ID = 999_001
    }
}
