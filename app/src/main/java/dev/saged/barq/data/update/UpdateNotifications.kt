package dev.saged.barq.data.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.saged.barq.MainActivity
import dev.saged.barq.R

/** The three update notifications: download progress, "downloaded, tap to install", and failure. */
object UpdateNotifications {
    private const val CHANNEL = "update"
    const val PROGRESS_ID = 999_500
    private const val READY_ID = 999_501
    private const val FAILED_ID = 999_502

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_update), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun builder(context: Context): NotificationCompat.Builder {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_barq)
            .setColor(ContextCompat.getColor(context, R.color.brand))
    }

    private fun open(context: Context, install: Boolean): PendingIntent = PendingIntent.getActivity(
        context, if (install) 1 else 0,
        Intent(context, MainActivity::class.java)
            .setAction(if (install) MainActivity.ACTION_INSTALL_UPDATE else MainActivity.ACTION_OPEN_UPDATE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun progress(context: Context, version: String, percent: Int): Notification =
        builder(context)
            .setContentTitle(context.getString(R.string.update_notif_downloading, version))
            .setContentText(context.getString(R.string.update_percent, percent))
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(context, install = false))
            .build()

    private fun canPost(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(context: Context, id: Int, n: Notification) {
        if (canPost(context)) runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    fun ready(context: Context, version: String) = post(
        context, READY_ID,
        builder(context)
            .setContentTitle(context.getString(R.string.update_notif_ready, version))
            .setContentText(context.getString(R.string.update_notif_ready_text))
            .setContentIntent(open(context, install = true))
            .setAutoCancel(true)
            .build(),
    )

    fun failed(context: Context) = post(
        context, FAILED_ID,
        builder(context)
            .setContentTitle(context.getString(R.string.update_notif_failed))
            .setContentText(context.getString(R.string.update_notif_failed_text))
            .setContentIntent(open(context, install = false))
            .setAutoCancel(true)
            .build(),
    )

    fun cancelReady(context: Context) {
        NotificationManagerCompat.from(context).apply {
            cancel(READY_ID)
            cancel(FAILED_ID)
        }
    }
}
