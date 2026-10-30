package dev.saged.barq.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.MainActivity
import dev.saged.barq.R
import dev.saged.barq.data.contacts.ContactsRepository
import dev.saged.barq.data.db.BatchCounts
import dev.saged.barq.data.db.done
import dev.saged.barq.data.db.ConversationDao
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.sms.SendErrors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversations: ConversationDao,
    private val messages: MessageDao,
    private val contacts: ContactsRepository,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val system get() = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        system.createNotificationChannels(
            listOf(
                NotificationChannel(CH_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_OTP, context.getString(R.string.channel_otp), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_otp_desc)
                },
                NotificationChannel(CH_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_SENDING, context.getString(R.string.channel_sending), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        )
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun threadIntent(threadId: Long): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("barq://thread/$threadId"))
            .putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun openThread(threadId: Long): PendingIntent = PendingIntent.getActivity(
        context, threadId.toInt(), threadIntent(threadId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun openApp(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(
        action: String,
        threadId: Long,
        extra: String? = null,
        mutable: Boolean = false,
        batchId: Long = 0,
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("barq://action/$action/$threadId/$batchId"))
            .putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, threadId)
            .putExtra(NotificationActionReceiver.EXTRA_BATCH_ID, batchId)
        if (extra != null) intent.putExtra(NotificationActionReceiver.EXTRA_VALUE, extra)
        val mutability = if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
    }

    // ------------------------------------------------------------------ conversations

    /** One notification per conversation, with the contact's photo and Copy-code, Reply, Mark-read. */
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
        val address = PhoneUtils.splitAddresses(conv.addresses).singleOrNull()
        val photo = address?.let { contacts.lookup(it)?.photoUri }?.let { loadCircle(it) }
        val icon = photo?.let { IconCompat.createWithBitmap(it) }
        val me = Person.Builder().setName(context.getString(R.string.me)).build()
        val sender = Person.Builder().setName(conv.displayName).setKey(conv.addresses).apply {
            if (icon != null) setIcon(icon)
        }.build()
        val style = NotificationCompat.MessagingStyle(me)
        unread.takeLast(6).forEach { style.addMessage(it.body.ifBlank { context.getString(R.string.media_placeholder) }, it.date, sender) }

        val shortcutId = pushShortcut(threadId, conv.displayName, sender, icon)
        val channel = when {
            otp != null -> CH_OTP
            conversationChannelExists(threadId) -> conversationChannelId(threadId)
            else -> CH_MESSAGES
        }

        val builder = NotificationCompat.Builder(context, channel)
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
            .setGroup(GROUP_MESSAGES)
        if (photo != null) builder.setLargeIcon(photo)
        if (shortcutId != null) builder.setShortcutId(shortcutId)

        if (otp != null) {
            builder.addAction(
                R.drawable.ic_copy,
                context.getString(R.string.copy_code_value, otp),
                action(NotificationActionReceiver.ACTION_COPY_OTP, threadId, otp)
            )
        }
        if (PhoneUtils.isReplyable(conv.addresses)) {
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
        updateSummary()
    }

    /** With two or more conversations notifying, Android shows them grouped under one summary. */
    private fun updateSummary() {
        if (!canPost()) return
        val active = runCatching {
            system.activeNotifications.count { it.notification.group == GROUP_MESSAGES && it.id != SUMMARY_ID }
        }.getOrDefault(0)
        if (active < 2) {
            manager.cancel(SUMMARY_ID)
            return
        }
        val n = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_barq)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_summary, active))
            .setGroup(GROUP_MESSAGES)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(openApp(SUMMARY_ID))
            .build()
        runCatching { manager.notify(SUMMARY_ID, n) }
    }

    fun cancel(threadId: Long) {
        manager.cancel(threadId.toInt())
        updateSummary()
    }

    /** Long-lived shortcut so Android shows the notification in its "Conversations" section. */
    private fun pushShortcut(threadId: Long, name: String, person: Person, icon: IconCompat?): String? = runCatching {
        val id = "thread_$threadId"
        val shortcut = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(name.ifBlank { "SMS" })
            .setLongLived(true)
            .setPerson(person)
            .setIcon(icon ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(threadIntent(threadId))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        id
    }.getOrNull()

    private fun loadCircle(uri: String): Bitmap? = runCatching {
        val src = context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            ?: return null
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val left = (src.width - size) / 2
        val top = (src.height - size) / 2
        canvas.drawBitmap(src, Rect(left, top, left + size, top + size), Rect(0, 0, size, size), paint)
        out
    }.getOrNull()

    // ------------------------------------------------------------------ per-conversation sound

    private fun conversationChannelId(threadId: Long) = "conv_$threadId"

    private fun conversationChannelExists(threadId: Long): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching { system.getNotificationChannel(conversationChannelId(threadId)) != null }.getOrDefault(false)

    /**
     * Opens Android's notification settings for this one conversation (sound, vibration…),
     * creating its own channel the first time.
     */
    suspend fun openConversationSettings(threadId: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val conv = conversations.get(threadId) ?: return
        val id = conversationChannelId(threadId)
        if (system.getNotificationChannel(id) == null) {
            val channel = NotificationChannel(id, conv.displayName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_conversation_desc)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setConversationId(CH_MESSAGES, "thread_$threadId")
            }
            runCatching { system.createNotificationChannel(channel) }
        }
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun deleteConversationChannel(threadId: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { system.deleteNotificationChannel(conversationChannelId(threadId)) }
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf("thread_$threadId")) }
    }

    // ------------------------------------------------------------------ sending

    suspend fun notifySendFailed(messageId: Long) {
        if (!canPost()) return
        val msg = messages.get(messageId) ?: return
        val conv = conversations.get(msg.threadId)
        val who = conv?.displayName ?: msg.address
        val reason = context.getString(SendErrors.reason(msg.errorCode))
        val text = context.getString(R.string.send_failed_text, who) + "\n" + reason
        val n = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_barq)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(context.getString(R.string.send_failed_title))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openThread(msg.threadId))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(FAILED_ID_BASE + msg.threadId.toInt(), n) }
    }

    /** Small notification shown while a single message is being sent in the background. */
    fun sendingNotification(): Notification =
        NotificationCompat.Builder(context, CH_SENDING)
            .setSmallIcon(R.drawable.ic_stat_barq)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(context.getString(R.string.status_sending))
            .setOngoing(true)
            .setSilent(true)
            .build()

    /** Live progress of a bulk send with Pause/Resume and Cancel. */
    fun bulkProgress(
        batchId: Long,
        counts: BatchCounts,
        preparing: Boolean,
        paused: Boolean,
        waitingLimit: Boolean = false,
        stalled: Boolean = false,
    ): Notification {
        val b = NotificationCompat.Builder(context, CH_SENDING)
            .setSmallIcon(R.drawable.ic_stat_barq)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(BULK_ID))
        when {
            preparing || counts.total == 0 -> b
                .setContentTitle(context.getString(R.string.bulk_preparing))
                .setProgress(0, 0, true)
            else -> {
                val title = if (paused) R.string.bulk_paused_title else R.string.bulk_sending_title
                val text = when {
                    stalled -> context.getString(R.string.bulk_stalled)
                    waitingLimit -> context.getString(R.string.bulk_waiting_limit)
                    else -> context.getString(R.string.bulk_counts, counts.sent, counts.failed, counts.pending)
                }
                b.setContentTitle(context.getString(title, counts.done, counts.total))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setProgress(counts.total, counts.done, false)
            }
        }
        if (batchId != 0L) {
            if (paused) {
                b.addAction(R.drawable.ic_done, context.getString(R.string.bulk_resume),
                    action(NotificationActionReceiver.ACTION_BATCH_RESUME, -1, batchId = batchId))
            } else {
                b.addAction(R.drawable.ic_done, context.getString(R.string.bulk_pause),
                    action(NotificationActionReceiver.ACTION_BATCH_PAUSE, -1, batchId = batchId))
            }
            b.addAction(R.drawable.ic_done, context.getString(R.string.bulk_cancel),
                action(NotificationActionReceiver.ACTION_BATCH_CANCEL, -1, batchId = batchId))
        }
        return b.build()
    }

    fun updateBulkProgress(
        batchId: Long,
        counts: BatchCounts,
        preparing: Boolean,
        paused: Boolean,
        waitingLimit: Boolean = false,
        stalled: Boolean = false,
    ) {
        if (!canPost()) return
        runCatching { manager.notify(BULK_ID, bulkProgress(batchId, counts, preparing, paused, waitingLimit, stalled)) }
    }

    fun notifyBulkFinished(batchId: Long, counts: BatchCounts) {
        if (!canPost()) return
        val b = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_barq)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(context.getString(R.string.bulk_done_title))
            .setContentText(context.getString(R.string.bulk_done_text, counts.sent, counts.failed))
            .setContentIntent(openApp(BULK_DONE_ID))
            .setAutoCancel(true)
        if (counts.failed > 0) {
            b.addAction(R.drawable.ic_reply, context.getString(R.string.bulk_resend_failed),
                action(NotificationActionReceiver.ACTION_BATCH_RESEND, -1, batchId = batchId))
        }
        runCatching { manager.notify(BULK_DONE_ID, b.build()) }
    }

    fun cancelBulkFinished() = manager.cancel(BULK_DONE_ID)

    fun notifyMmsDisabled(from: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_barq)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(context.getString(R.string.mms_disabled_title))
            .setContentText(context.getString(R.string.mms_disabled_text, from))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.mms_disabled_text, from)))
            .setContentIntent(openApp(7))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(MMS_DISABLED_ID, n) }
    }

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_OTP = "otp"
        const val CH_STATUS = "status"
        const val CH_SENDING = "sending"
        const val GROUP_MESSAGES = "barq_messages"
        const val SENDING_ID = 999_300
        const val BULK_ID = 999_200
        private const val BULK_DONE_ID = 999_201
        private const val SUMMARY_ID = 999_100
        private const val FAILED_ID_BASE = 1_000_000
        private const val MMS_DISABLED_ID = 999_001
    }
}
