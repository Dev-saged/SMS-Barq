package dev.saged.barq.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.BuildConfig
import dev.saged.barq.di.AppScope
import dev.saged.barq.util.CrashLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One place for the whole update: the found version, the background download into Barq's own
 * folder, the install through the system installer, and the cleanup afterwards.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checker: UpdateChecker,
    @AppScope private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data class Available(val update: UpdateChecker.Result.Available) : State
        data class Downloading(val update: UpdateChecker.Result.Available, val percent: Int) : State
        data class Ready(val update: UpdateChecker.Result.Available) : State
        data class Installing(val update: UpdateChecker.Result.Available) : State
        data class Failed(val update: UpdateChecker.Result.Available, val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The update sheet is open (asked for by the user, or by tapping the pill / the notification). */
    private val _sheet = MutableStateFlow(false)
    val sheet: StateFlow<Boolean> = _sheet.asStateFlow()

    fun showSheet(show: Boolean) {
        _sheet.value = show && _state.value !is State.Idle
    }

    /** "Check for updates": the sheet opens by itself when a newer version is found. */
    suspend fun check(): UpdateChecker.Result {
        val result = checker.check()
        if (result is UpdateChecker.Result.Available) {
            if (_state.value.let { it is State.Idle || it is State.Available || it is State.Failed }) {
                _state.value = State.Available(result)
            }
            _sheet.value = true
        }
        return result
    }

    private fun current(): UpdateChecker.Result.Available? = when (val s = _state.value) {
        is State.Available -> s.update
        is State.Failed -> s.update
        else -> null
    }

    /** Starts the background download (a foreground service, so it survives closing the app). */
    fun start() {
        val update = current() ?: return
        apkFile(update.version).takeIf { valid(it) }?.let {
            _state.value = State.Ready(update)
            return
        }
        _state.value = State.Downloading(update, 0)
        runCatching { ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java)) }
            .onFailure {
                CrashLog.warn("update-start", it)
                _state.value = State.Failed(update, it.javaClass.simpleName)
            }
    }

    /** Runs inside the download service. */
    suspend fun download() = withContext(Dispatchers.IO) {
        val update = (_state.value as? State.Downloading)?.update ?: return@withContext
        val dir = updatesDir()
        val part = File(dir, "Barq-${update.version}.apk.part")
        try {
            val conn = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "Barq-SMS")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                var done = 0L
                var last = -1
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 99) else 0
                            if (pct != last) {
                                last = pct
                                _state.value = State.Downloading(update, pct)
                            }
                        }
                    }
                }
                if (total > 0 && part.length() != total) throw IOException("incomplete")
            } finally {
                conn.disconnect()
            }
            if (!valid(part)) throw IOException("invalid apk")
            val file = apkFile(update.version)
            file.delete()
            if (!part.renameTo(file)) throw IOException("rename")
            _state.value = State.Ready(update)
        } catch (e: CancellationException) {
            part.delete()
            _state.value = State.Available(update)
            throw e
        } catch (e: Exception) {
            part.delete()
            CrashLog.warn("update-download", e)
            _state.value = State.Failed(update, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Installs the downloaded file. Without the "install unknown apps" permission the system page
     * for it opens first; the user comes back and taps install again.
     */
    fun install(from: Context = context) {
        val update = (_state.value as? State.Ready)?.update ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                from.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        val file = apkFile(update.version)
        if (!valid(file)) {
            _state.value = State.Failed(update, "missing file")
            return
        }
        _state.value = State.Installing(update)
        scope.launch(Dispatchers.IO) {
            runCatching { commit(file) }.onFailure {
                CrashLog.warn("update-install", it)
                _state.value = State.Failed(update, it.message ?: it.javaClass.simpleName)
            }
        }
    }

    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            // Installed "from a store": Android does not apply the restricted-settings block to the update.
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("barq.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val result = PendingIntent.getBroadcast(
                context, id,
                Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                flags,
            )
            session.commit(result.intentSender)
        }
    }

    /** Result of the system installer (called by [UpdateInstallReceiver]). */
    fun installEnded(status: Int, message: String?) {
        val update = (_state.value as? State.Installing)?.update ?: return
        _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            State.Ready(update)
        } else {
            State.Failed(update, "$status${message?.let { ": $it" }.orEmpty()}")
        }
    }

    /**
     * At every start of Barq: an update installed meanwhile is deleted right away (to give the
     * space back), and one downloaded earlier but not installed yet shows as ready again.
     */
    fun restore() {
        val installed = BuildConfig.VERSION_CODE.toLong()
        val dir = updatesDir()
        val now = System.currentTimeMillis()
        var ready: UpdateChecker.Result.Available? = null
        dir.listFiles().orEmpty().forEach { f ->
            if (f.name.endsWith(".part")) {
                if (now - f.lastModified() > PART_TTL_MS && _state.value !is State.Downloading) f.delete()
                return@forEach
            }
            val info = archive(f)
            val code = info?.let { PackageInfoCompat.getLongVersionCode(it) }
            if (info == null || code == null || code <= installed) {
                f.delete()
            } else if (_state.value is State.Idle) {
                ready = UpdateChecker.Result.Available(info.versionName.orEmpty(), "", "", code.toInt())
            }
        }
        ready?.let { _state.value = State.Ready(it) }
        if (_state.value is State.Idle) UpdateNotifications.cancelReady(context)
    }

    private fun updatesDir(): File = File(context.filesDir, "updates").apply { mkdirs() }

    private fun apkFile(version: String) = File(updatesDir(), "Barq-$version.apk")

    @Suppress("DEPRECATION")
    private fun archive(file: File) = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            context.packageManager.getPackageArchiveInfo(file.path, 0)
        }
    }.getOrNull()

    /** A real Barq package, newer than the installed one — never anything else is offered for install. */
    private fun valid(file: File): Boolean {
        if (!file.isFile) return false
        val info = archive(file) ?: return false
        return info.packageName == context.packageName &&
            PackageInfoCompat.getLongVersionCode(info) > BuildConfig.VERSION_CODE
    }

    private companion object {
        const val PART_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
