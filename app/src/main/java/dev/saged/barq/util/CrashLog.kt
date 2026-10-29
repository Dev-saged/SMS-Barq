package dev.saged.barq.util

import android.content.Context
import android.os.Build
import android.util.Log
import dev.saged.barq.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline crash log. If Barq ever crashes, the full reason is saved on the phone; the owner can
 * copy it from Settings → About → Crash log and send it, instead of guessing what happened.
 * Important background errors are kept too, as warnings. Nothing ever leaves the phone by itself.
 */
object CrashLog {

    data class Entry(val file: String, val time: Long, val fatal: Boolean, val title: String, val text: String)

    private const val MAX_ENTRIES = 20
    private const val TAG = "Barq"

    @Volatile private var dir: File? = null

    /** The screen on screen when something happens (set by the navigation host). */
    @Volatile var currentScreen: String = "-"

    fun install(context: Context) {
        val d = File(context.filesDir, "crashes").apply { mkdirs() }
        dir = d
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                write(fatal = true, tag = thread.name, error = error)
                File(d, "pending").writeText("1")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Records a non-fatal error from background work (never throws). */
    fun warn(tag: String, error: Throwable) {
        Log.w(TAG, tag, error)
        runCatching { write(fatal = false, tag = tag, error = error) }
    }

    private fun write(fatal: Boolean, tag: String, error: Throwable) {
        val d = dir ?: return
        val now = System.currentTimeMillis()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine(if (fatal) "CRASH" else "WARNING ($tag)")
            appendLine("Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now)))
            appendLine("App: Barq ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Screen: $currentScreen")
            appendLine()
            append(trace.take(16_000))
        }
        File(d, "${if (fatal) "crash" else "warn"}_$now.txt").writeText(text)
        d.listFiles { f -> f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_ENTRIES)
            ?.forEach { it.delete() }
    }

    fun entries(): List<Entry> {
        val d = dir ?: return emptyList()
        return d.listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                runCatching {
                    val text = f.readText()
                    val firstTraceLine = text.lineSequence().dropWhile { it.isNotBlank() }.drop(1).firstOrNull().orEmpty()
                    Entry(
                        file = f.name,
                        time = f.lastModified(),
                        fatal = f.name.startsWith("crash"),
                        title = firstTraceLine.take(160),
                        text = text,
                    )
                }.getOrNull()
            }
    }

    /** Everything, formatted to paste into a chat with the developer or an AI assistant. */
    fun exportText(): String = entries().joinToString("\n\n----------------------------------------\n\n") { it.text }

    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
    }

    /** True once after a crash, so the inbox can offer to open the log. */
    fun hasUnseenCrash(): Boolean = dir?.let { File(it, "pending").exists() } ?: false

    fun markSeen() {
        dir?.let { File(it, "pending").delete() }
    }
}
