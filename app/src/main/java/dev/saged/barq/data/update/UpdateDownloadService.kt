package dev.saged.barq.data.update

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dev.saged.barq.di.barq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Hosts the update download in the foreground, so it goes on after Barq is closed. Shows the
 * percentage in a notification and, when done, "downloaded, tap to install".
 */
class UpdateDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = applicationContext.barq.updates()
        val first = manager.state.value as? UpdateManager.State.Downloading
        ServiceCompat.startForeground(
            this, UpdateNotifications.PROGRESS_ID,
            UpdateNotifications.progress(this, first?.update?.version.orEmpty(), first?.percent ?: 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (job?.isActive == true) return START_NOT_STICKY
        job = scope.launch {
            launch {
                manager.state.takeWhile { it is UpdateManager.State.Downloading }.collect { s ->
                    s as UpdateManager.State.Downloading
                    runCatching {
                        getSystemService(android.app.NotificationManager::class.java)
                            .notify(UpdateNotifications.PROGRESS_ID, UpdateNotifications.progress(this@UpdateDownloadService, s.update.version, s.percent))
                    }
                }
            }
            manager.download()
            when (val end = manager.state.value) {
                is UpdateManager.State.Ready -> UpdateNotifications.ready(this@UpdateDownloadService, end.update.version)
                is UpdateManager.State.Failed -> UpdateNotifications.failed(this@UpdateDownloadService)
                else -> Unit
            }
            ServiceCompat.stopForeground(this@UpdateDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
