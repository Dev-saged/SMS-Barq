package dev.saged.barq.data.update

import dev.saged.barq.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Check for updates" — the only time Barq uses the internet (with the update download), and only
 * when the user taps the button, never in the background. Reads the latest official release on
 * GitHub ("v1.5.3", with its Arabic notes), never the test builds, and compares its version and
 * build code with the installed one. A failure carries the real reason of every attempt.
 */
@Singleton
class UpdateChecker @Inject constructor() {

    sealed interface Result {
        data class Available(val version: String, val apkUrl: String, val notes: String, val code: Int? = null) : Result
        data class UpToDate(val version: String) : Result
        data class Failed(val reason: String) : Result
    }

    private class Release(val version: String, val code: Int?, val apkUrl: String, val notes: String)

    private class HttpFail(val status: Int) : IOException("HTTP $status")

    /**
     * Three independent ways to learn the latest release, so one blocked host or GitHub's anonymous
     * API limit does not hide a new version: (1) version.json published with the release,
     * (2) the redirect of github.com/.../releases/latest, (3) the REST API.
     */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val layers = listOf<Pair<String, () -> Release>>("A" to ::viaAsset, "B" to ::viaRedirect, "C" to ::viaApi)
        for ((name, layer) in layers) {
            val release = try {
                layer()
            } catch (e: Exception) {
                errors += "$name: ${describe(e)}"
                continue
            }
            return@withContext decide(release)
        }
        Result.Failed(errors.joinToString("\n"))
    }

    private fun decide(r: Release): Result =
        if (isUpdate(r.version, r.code, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) {
            Result.Available(r.version, r.apkUrl, cleanNotes(r.notes), r.code)
        } else {
            Result.UpToDate(r.version)
        }

    private fun describe(e: Exception): String = (e as? HttpFail)?.message ?: e.javaClass.simpleName

    private fun open(url: String, follow: Boolean = true) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = follow
        setRequestProperty("User-Agent", "Barq-SMS")
    }

    private fun get(url: String, accept: String? = null): String {
        val conn = open(url).apply { accept?.let { setRequestProperty("Accept", it) } }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpFail(conn.responseCode)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun apkUrl(tag: String, name: String = "Barq-$tag.apk") = "$REPO/releases/download/$tag/$name"

    private fun viaAsset(): Release {
        val json = Json.parseToJsonElement(get("$PAGE/download/version.json")).jsonObject
        val version = json["version"]?.jsonPrimitive?.contentOrNull ?: throw IOException("no version")
        if (parts(version).isEmpty()) throw IOException("bad version")
        val apk = json["apk"]?.jsonPrimitive?.contentOrNull ?: "Barq-v$version.apk"
        return Release(
            version = version,
            code = json["code"]?.jsonPrimitive?.intOrNull,
            apkUrl = apkUrl("v$version", apk),
            notes = json["notes"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private fun viaRedirect(): Release {
        val conn = open(PAGE, follow = false)
        val tag = try {
            val status = conn.responseCode
            if (status !in 300..399) throw HttpFail(status)
            val location = conn.getHeaderField("Location") ?: throw IOException("no location")
            URL(URL(PAGE), location).path.substringAfterLast("/tag/", "")
        } finally {
            conn.disconnect()
        }
        val version = tag.removePrefix("v")
        if (parts(version).isEmpty()) throw IOException("bad tag")
        val notes = runCatching { get("$RAW/docs/releases/$tag.md") }.getOrDefault("")
        return Release(version, null, apkUrl(tag), notes)
    }

    private fun viaApi(): Release {
        val json = Json.parseToJsonElement(get(API, "application/vnd.github+json")).jsonObject
        val tag = json["tag_name"]?.jsonPrimitive?.contentOrNull ?: throw IOException("no tag")
        val version = tag.removePrefix("v")
        if (parts(version).isEmpty()) throw IOException("bad tag")
        val assets = json["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val name = { a: kotlinx.serialization.json.JsonObject -> a["name"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        val apk = assets.firstOrNull { name(it) == "Barq-v$version.apk" } ?: assets.firstOrNull { name(it).endsWith(".apk") }
        return Release(
            version = version,
            code = null,
            apkUrl = apk?.get("browser_download_url")?.jsonPrimitive?.contentOrNull ?: apkUrl(tag),
            notes = json["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    companion object {
        private const val REPO = "https://github.com/Dev-saged/SMS-Barq"
        private const val RAW = "https://raw.githubusercontent.com/Dev-saged/SMS-Barq/main"
        private const val API = "https://api.github.com/repos/Dev-saged/SMS-Barq/releases/latest"
        const val PAGE = "$REPO/releases/latest"
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

        /** Newer by version number, or the same version rebuilt with a higher build code. */
        fun isUpdate(candidate: String, candidateCode: Int?, installed: String, installedCode: Int): Boolean =
            isNewer(candidate, installed) ||
                (!isNewer(installed, candidate) && candidateCode != null && candidateCode > installedCode)

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
