package dev.saged.barq.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.data.ConversationRepository
import dev.saged.barq.data.backup.BackupManager
import dev.saged.barq.data.settings.BackupFrequency
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.util.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MaintenanceScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    /** A light job every 6 hours: scheduled backup when due, recycle-bin cleanup, temp files. */
    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("barq-maintenance", ExistingPeriodicWorkPolicy.KEEP, request)

        // Once a month, only while charging and not in use: give the space of deleted messages back.
        val compact = PeriodicWorkRequestBuilder<DbCompactWorker>(30, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("barq-db-compact", ExistingPeriodicWorkPolicy.KEEP, compact)
    }
}

@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: ConversationRepository,
    private val backup: BackupManager,
    private val settings: SettingsRepository,
    private val db: dev.saged.barq.data.db.BarqDatabase,
    private val snapshot: dev.saged.barq.data.backup.SettingsSnapshot,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Keeps the small lists-and-settings file fresh for Android's own (encrypted) backup.
        runCatching { snapshot.write() }
        runCatching { repository.purgeRecycleBin() }
        runCatching { repository.purgeEmpty() }

        // Temporary MMS PDUs older than a day.
        val day = 24 * 60 * 60 * 1000L
        File(applicationContext.cacheDir, "mms").listFiles()
            ?.filter { System.currentTimeMillis() - it.lastModified() > day }
            ?.forEach { it.delete() }

        // Keep Barq light: leftovers in the cache older than 3 days go, and the database journal is
        // folded back into the main file so it never grows on its own.
        val threeDays = 3 * day
        applicationContext.cacheDir.walkBottomUp()
            .filter { it != applicationContext.cacheDir && System.currentTimeMillis() - it.lastModified() > threeDays }
            .forEach { if (it.isFile || it.list().isNullOrEmpty()) it.delete() }
        runCatching { db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }

        val s = settings.current()
        val interval = when (s.backupFrequency) {
            BackupFrequency.OFF -> null
            BackupFrequency.DAILY -> day - 60 * 60 * 1000L
            BackupFrequency.WEEKLY -> 7 * day - 60 * 60 * 1000L
        }
        if (interval != null && System.currentTimeMillis() - s.lastBackupAt >= interval) {
            backup.runAutomaticBackup()
        }
        return Result.success()
    }
}

/**
 * Deleting conversations does not make the database file smaller by itself: SQLite keeps the free
 * pages for later. This tidies the search index and rebuilds the file at its real size.
 */
@HiltWorker
class DbCompactWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: dev.saged.barq.data.db.BarqDatabase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val file = applicationContext.getDatabasePath("barq.db")
        // VACUUM writes a full copy first: skip this month when the phone is short on space.
        if (applicationContext.filesDir.usableSpace < file.length() * 2 + 50L * 1024 * 1024) {
            return@withContext Result.success()
        }
        runCatching {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("INSERT INTO messages_fts(messages_fts) VALUES('optimize')")
            sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            sql.execSQL("VACUUM")
        }.onFailure { CrashLog.warn("db-compact", it) }
        Result.success()
    }
}
