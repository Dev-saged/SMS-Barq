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
 * button (never in the background). Reads the "latest-build" release on GitHub, whose text says
 * "(build N)", and compares N with this installed build.
 */
@Singleton
class UpdateChecker @Inject constructor() {

    sealed interface Result {
        data class Available(val build: Int, val apkUrl: String) : Result
        data class UpToDate(val build: Int) : Result
        data object Failed : Result
    }

    /** GitHub run number of this installed build (0 for builds made outside GitHub). */
    val installedBuild: Int get() = BuildConfig.VERSION_CODE - BuildConfig.BASE_VERSION_CODE

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
            val body = json["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val build = BUILD.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return Result.Failed
            val apk = json["assets"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == APK_NAME }
                ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
                ?: PAGE
            return if (build > installedBuild) Result.Available(build, apk) else Result.UpToDate(build)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val API = "https://api.github.com/repos/Dev-saged/SMS-Barq/releases/tags/latest-build"
        const val PAGE = "https://github.com/Dev-saged/SMS-Barq/releases/tag/latest-build"
        private const val APK_NAME = "Barq-latest.apk"
        private val BUILD = Regex("build\\s*(\\d+)")
    }
}
