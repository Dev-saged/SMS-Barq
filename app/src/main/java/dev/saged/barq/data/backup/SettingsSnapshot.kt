package dev.saged.barq.data.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.util.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A small file with Barq's own lists and settings (groups, templates, blocked numbers and words,
 * category rules, auto-replies, preferences) — never messages. It is the only thing Android's
 * backup takes from Barq (see res/xml/backup_rules.xml and data_extraction_rules.xml), and only
 * when the backup is end-to-end encrypted. Messages move with the phone's own SMS store.
 *
 * On a new phone (or after reinstalling) Android puts the file back before Barq first starts;
 * Barq then applies it once.
 */
@Singleton
class SettingsSnapshot @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: BackupManager,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()

    private val file: File get() = File(File(context.filesDir, DIR).apply { mkdirs() }, NAME)

    /** At start-up: applies a snapshot that Android restored, the first time this install runs. */
    suspend fun restoreIfNew(): Boolean = mutex.withLock { restoreLocked() }

    /** Refreshes the file (maintenance does it every few hours; Android backs it up about daily). */
    suspend fun write() = mutex.withLock {
        restoreLocked() // never overwrite a restored file before it was applied
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(file.parentFile, "$NAME.tmp")
                tmp.outputStream().use { backup.export(it, compressed = true, withMessages = false) }
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }.onFailure { CrashLog.warn("snapshot-write", it) }
        }
    }

    private suspend fun restoreLocked(): Boolean {
        if (settings.current().snapshotChecked) return false
        // Before 1.5.3 no snapshot was ever written, so a file here came from Android's backup.
        val restored = withContext(Dispatchers.IO) {
            runCatching {
                val f = file
                if (!f.exists() || f.length() == 0L) return@runCatching false
                val snapshot = f.inputStream().use { backup.read(it) }
                if (snapshot.format != "barq-backup") return@runCatching false
                backup.restoreSnapshot(snapshot)
                true
            }.onFailure { CrashLog.warn("snapshot-restore", it) }.getOrDefault(false)
        }
        settings.setSnapshotChecked(true)
        return restored
    }

    private companion object {
        const val DIR = "autobackup"
        const val NAME = "barq-settings.json.gz"
    }
}
