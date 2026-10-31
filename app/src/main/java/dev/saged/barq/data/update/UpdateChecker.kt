package dev.saged.barq.data.update

import dev.saged.barq.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Check for updates" — the only time Barq uses the internet, and only when the user taps the
 * button (never in the background). Reads the latest official release on GitHub ("v1.5.3", with
 * its Arabic notes), never the test builds, and compares its version with the installed one.
 */
@Singleton
class UpdateChecker @Inject constructor() {

    sealed interface Result {
        data class Available(val version: String, val apkUrl: String, val notes: String) : Result
        data class UpToDate(val version: String) : Result
        data object Failed : Result
    }

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        runCatching { fetch() }.getOrDefault(Result.Failed)
    }

    private fun fetch(): Result {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Barq-SMS")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return Result.Failed
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = Json.parseToJsonElement(text).jsonObject
            val version = json["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v") ?: return Result.Failed
            if (parts(version).isEmpty()) return Result.Failed
            val assets = json["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            val apk = assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "Barq-v$version.apk" }
                ?: assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull.orEmpty().endsWith(".apk") }
            val url = apk?.get("browser_download_url")?.jsonPrimitive?.contentOrNull ?: PAGE
            val notes = cleanNotes(json["body"]?.jsonPrimitive?.contentOrNull.orEmpty())
            return if (isNewer(version, BuildConfig.VERSION_NAME)) Result.Available(version, url, notes)
            else Result.UpToDate(version)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val API = "https://api.github.com/repos/Dev-saged/SMS-Barq/releases/latest"
        const val PAGE = "https://github.com/Dev-saged/SMS-Barq/releases/latest"
        private const val MAX_NOTES = 1_200

        private fun parts(version: String): List<Int> =
            version.trim().removePrefix("v").split('.').map { it.takeWhile(Char::isDigit) }
                .takeIf { p -> p.isNotEmpty() && p.all { it.isNotEmpty() } }
                ?.map { it.toInt() }
                .orEmpty()

        /** "1.5.10" is newer than "1.5.9"; a missing part counts as 0 ("1.6" = "1.6.0"). */
        fun isNewer(candidate: String, installed: String): Boolean {
            val a = parts(candidate)
            val b = parts(installed)
            if (a.isEmpty()) return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /** The release notes as plain lines: no title, no markdown, no "going back" footnote. */
        fun cleanNotes(markdown: String): String = markdown.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(">") }
            .joinToString("\n") { line ->
                val text = line.replace("**", "").replace("`", "").replace("‎", "")
                if (text.startsWith("- ") || text.startsWith("* ")) "• " + text.drop(2) else text
            }
            .let { if (it.length > MAX_NOTES) it.take(MAX_NOTES).substringBeforeLast('\n') + "\n…" else it }
    }
}
