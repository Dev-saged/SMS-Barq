package dev.saged.barq.sms.gateway

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.BuildConfig
import dev.saged.barq.R
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.sms.SendEngine
import dev.saged.barq.util.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Barq's local SMS gateway: a tiny HTTP server for bots and scripts on the same network (or the
 * same phone). It speaks SMS Sendr's API — same paths, JSON body, key headers, answers and status
 * codes — so a bot written for Sendr works unchanged. The difference: there is no "40 requests a
 * minute" cap and no delay per request. Every request is written to Barq's queue at once and
 * answered right away; Barq's sending engine then sends them as fast as Android allows.
 *
 * Only local addresses may connect (Wi-Fi / hotspot / the phone itself); nothing goes online.
 */
@Singleton
class GatewayServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: SendEngine,
    private val settings: SettingsRepository,
) {
    data class Status(
        val running: Boolean = false,
        /** The phone's address on the local network ("192.168.1.5"), null without a network. */
        val address: String? = null,
        val port: Int = 0,
        /** Messages accepted into the queue since the gateway started. */
        val received: Int = 0,
        /** Requests refused (bad key, bad number, duplicates…). */
        val rejected: Int = 0,
        /** Why the gateway could not start (port in use…). */
        val error: String? = null,
        /** The batch the current burst of requests joins (followed like a bulk send). */
        val batchId: Long = 0,
        val lastAt: Long = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val _accepted = Channel<Unit>(Channel.CONFLATED)

    /** Signals that new messages are waiting, so the service drains the queue. */
    val accepted: ReceiveChannel<Unit> get() = _accepted

    private var socket: ServerSocket? = null
    private var job: Job? = null
    private val batchMutex = Mutex()

    @Volatile private var key = ""
    @Volatile private var dedupe = true

    /** Recent (number + text) → time, for the duplicate check. */
    private val recent = LinkedHashMap<String, Long>()

    /** Starts listening on [port]. Returns false (with [Status.error]) when the port can't be used. */
    @Synchronized
    fun start(scope: CoroutineScope, port: Int, key: String, dedupe: Boolean): Boolean {
        stop()
        configure(key, dedupe)
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), BACKLOG)
            }
        } catch (t: Throwable) {
            CrashLog.warn("gateway-bind", t)
            _status.value = Status(port = port, error = t.message ?: t.javaClass.simpleName)
            return false
        }
        socket = server
        _status.value = Status(running = true, address = lanAddress(), port = port)
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try {
                    server.accept()
                } catch (t: Throwable) {
                    break // closed by stop()
                }
                launch { runCatching { handle(client) }.onFailure { CrashLog.warn("gateway-request", it) } }
            }
        }
        return true
    }

    /** New key or duplicate setting, applied without restarting. */
    fun configure(key: String, dedupe: Boolean) {
        this.key = key
        this.dedupe = dedupe
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        job?.cancel()
        job = null
        _status.update { it.copy(running = false) }
    }

    /** The network may change (Wi-Fi ↔ hotspot): refreshes the shown address. */
    fun refreshAddress() {
        if (_status.value.running) _status.update { it.copy(address = lanAddress()) }
    }

    // ---------------------------------------------------------------- HTTP

    private class Request(val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: ByteArray)

    private class HttpError(val code: Int, message: String) : Exception(message)

    private suspend fun handle(client: Socket) = client.use { s ->
        s.soTimeout = READ_TIMEOUT_MS
        s.tcpNoDelay = true
        val out = BufferedOutputStream(s.getOutputStream())
        if (!isLocal(s.inetAddress)) {
            respond(out, 403, result(false, false, "forbidden", "Only devices on the local network can use this gateway"))
            return@use
        }
        val req = try {
            readRequest(BufferedInputStream(s.getInputStream()), out)
        } catch (e: HttpError) {
            respond(out, e.code, result(false, false, "bad_request", e.message))
            return@use
        } catch (t: Throwable) {
            return@use // the client went away
        }
        val path = req.path.trimEnd('/').ifEmpty { "/" }
        val browser = req.headers["accept"]?.contains("text/html") == true
        when {
            req.method == "OPTIONS" -> respond(out, 204, "")
            path in SEND_PATHS ->
                if (req.method == "POST") send(req, out)
                // Someone opened the API address in a browser: show the panel instead of an error.
                else if (browser) redirect(out, "/" + req.query.let { if (it.isEmpty()) "" else "?$it" })
                else respond(out, 405, result(false, false, "method_not_allowed", "Use POST"))
            path == "/health" || path == "/api/health" -> respond(out, 200, health())
            path == "/api/status" -> status(req, out)
            req.method != "GET" -> respond(out, 405, result(false, false, "method_not_allowed", "Use GET"))
            path == "/" || path == "/g" || path == "/panel" -> respond(out, 200, page(), "text/html; charset=utf-8")
            path == "/favicon.svg" -> respond(out, 200, FAVICON, "image/svg+xml", cache = true)
            path == "/favicon.ico" -> respond(out, 204, "")
            path in FONTS -> font(out, FONTS.getValue(path))
            else -> respond(out, 404, result(false, false, "not_found", "Not found"))
        }
    }

    private fun authorized(req: Request): Boolean {
        val given = req.headers["x-api-key"]?.trim()
            ?: req.headers["authorization"]?.trim()?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else null }
        return !given.isNullOrEmpty() && key.isNotEmpty() && sameKey(given, key)
    }

    /** Live numbers for the web panel: what came in, and how the current burst is being sent. */
    private suspend fun status(req: Request, out: OutputStream) {
        if (!authorized(req)) return respond(out, 401, result(false, false, "unauthorized", "Invalid or missing API key"))
        val s = _status.value
        val c = if (s.batchId != 0L) runCatching { engine.batchCounts(s.batchId) }.getOrNull() else null
        val batch = JSONObject()
        if (c != null) batch.put("total", c.total).put("sent", c.sent).put("failed", c.failed).put("pending", c.pending).put("delivered", c.delivered)
        respond(
            out, 200,
            JSONObject().put("running", s.running).put("received", s.received).put("rejected", s.rejected)
                .put("version", BuildConfig.VERSION_NAME).put("batch", batch).toString(),
        )
    }

    private suspend fun send(req: Request, out: OutputStream) {
        if (!authorized(req)) {
            return reject(out, 401, "unauthorized", "Invalid or missing API key")
        }
        if (req.body.isEmpty()) return reject(out, 400, "bad_request", "JSON body is required")
        val json = try {
            JSONObject(String(req.body, Charsets.UTF_8))
        } catch (t: Throwable) {
            return reject(out, 400, "bad_request", "Invalid JSON body")
        }
        val rawPhone = json.firstString("phone", "to", "number", "recipient")
        val message = json.firstString("message", "text", "body").trim()
        val phone = PhoneUtils.localPs(rawPhone)
        if (!PHONE.matches(phone)) return reject(out, 422, "invalid_phone", "Invalid phone number")
        if (message.isEmpty()) return reject(out, 422, "invalid_message", "Message is required")
        if (message.length > MAX_MESSAGE) return reject(out, 422, "invalid_message", "Message is too long")

        val dupKey = PhoneUtils.matchKey(phone) + '\u0000' + message
        if (dedupe && !reserve(dupKey)) {
            _status.update { it.copy(rejected = it.rejected + 1) }
            return respond(out, 409, result(false, true, "duplicate", "Duplicate message", phone))
        }
        val queued = try {
            engine.enqueueGateway(phone, message, batchFor(System.currentTimeMillis()))
        } catch (t: Throwable) {
            CrashLog.warn("gateway-enqueue", t)
            forget(dupKey)
            return reject(out, 500, "queue_error", "Could not queue the message, try again")
        }
        if (!queued) {
            forget(dupKey)
            return reject(out, 422, "invalid_message", "Message could not be queued")
        }
        _status.update { it.copy(received = it.received + 1) }
        _accepted.trySend(Unit)
        respond(out, 200, result(true, false, "accepted", null, phone))
    }

    private fun reject(out: OutputStream, code: Int, detail: String, error: String) {
        _status.update { it.copy(rejected = it.rejected + 1) }
        respond(out, code, result(false, false, detail, error))
    }

    /** One batch per burst: requests less than [BURST_GAP_MS] apart are followed together. */
    private suspend fun batchFor(now: Long): Long = batchMutex.withLock {
        val s = _status.value
        if (s.batchId != 0L && now - s.lastAt < BURST_GAP_MS) {
            _status.update { it.copy(lastAt = now) }
            return@withLock s.batchId
        }
        settings.setLastBatchId(now)
        _status.update { it.copy(batchId = now, lastAt = now) }
        now
    }

    /** True when this number + text was not seen recently (and remembers it). */
    private fun reserve(k: String): Boolean = synchronized(recent) {
        val now = System.currentTimeMillis()
        val it = recent.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value > DEDUPE_WINDOW_MS || recent.size > DEDUPE_MAX) it.remove() else break
        }
        val seen = recent[k]
        if (seen != null && now - seen <= DEDUPE_WINDOW_MS) return@synchronized false
        recent.remove(k)
        recent[k] = now
        true
    }

    private fun forget(k: String) = synchronized(recent) { recent.remove(k) }

    private fun health(): String = JSONObject()
        .put("status", "ok")
        .put("service", "Barq")
        .put("compatible", "SMS Sendr")
        .put("version", BuildConfig.VERSION_NAME)
        .put("running", true)
        .put("received", _status.value.received)
        .put("time", iso(System.currentTimeMillis()))
        .toString()

    private fun readRequest(input: InputStream, out: OutputStream): Request {
        val line = readLine(input) ?: throw HttpError(400, "Bad request")
        val parts = line.split(' ')
        if (parts.size < 2) throw HttpError(400, "Bad request")
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (headers.size >= MAX_HEADERS) throw HttpError(400, "Too many headers")
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase(Locale.ROOT)] = h.substring(i + 1).trim()
        }
        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1].substringBefore('#')
        val path = target.substringBefore('?')
        val query = if ('?' in target) target.substringAfter('?') else ""
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) throw HttpError(413, "Request body is too large")
        if ((chunked || length > 0) && headers["expect"].equals("100-continue", ignoreCase = true)) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
        val body = if (chunked) readChunked(input) else readExactly(input, length)
        return Request(method, path, query, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > MAX_LINE) throw HttpError(400, "Header line is too long")
        }
        return sb.toString()
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r == -1) throw HttpError(400, "Body ended early")
            off += r
        }
        return buf
    }

    private fun readChunked(input: InputStream): ByteArray {
        val all = java.io.ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw HttpError(400, "Bad chunk")
            if (size == 0) {
                while (!readLine(input).isNullOrEmpty()) Unit // trailers
                break
            }
            if (all.size() + size > MAX_BODY) throw HttpError(413, "Request body is too large")
            all.write(readExactly(input, size))
            readLine(input) // CRLF after the chunk
        }
        return all.toByteArray()
    }

    @Volatile private var pageCache: String? = null

    /** The web panel (assets/gateway.html), read once. */
    private fun page(): String = pageCache ?: runCatching {
        context.assets.open("gateway.html").bufferedReader().use { it.readText() }
    }.getOrElse { "<h1>Barq</h1>" }.also { pageCache = it }

    private val fontCache = HashMap<Int, ByteArray>()

    /** Barq's own font, so the panel looks right even with no internet. */
    private fun font(out: OutputStream, res: Int) {
        val bytes = synchronized(fontCache) {
            fontCache.getOrPut(res) { runCatching { context.resources.openRawResource(res).use { it.readBytes() } }.getOrDefault(ByteArray(0)) }
        }
        if (bytes.isEmpty()) return respond(out, 404, "")
        respond(out, 200, bytes, "font/ttf", cache = true)
    }

    private fun redirect(out: OutputStream, to: String) {
        runCatching {
            out.write("HTTP/1.1 302 Found\r\nLocation: $to\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    private fun respond(out: OutputStream, code: Int, body: String, type: String = "application/json; charset=utf-8", cache: Boolean = false) =
        respond(out, code, body.toByteArray(Charsets.UTF_8), type, cache)

    private fun respond(out: OutputStream, code: Int, bytes: ByteArray, type: String, cache: Boolean) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
            if (code != 204) append("Content-Type: ").append(type).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, X-Api-Key, Authorization\r\n")
            append(if (cache) "Cache-Control: public, max-age=86400\r\n" else "Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            out.write(head.toByteArray(Charsets.US_ASCII))
            if (bytes.isNotEmpty()) out.write(bytes)
            out.flush()
        }
    }

    companion object {
        private val SEND_PATHS = setOf("/send", "/api/send")
        private val FONTS = mapOf("/f/r.ttf" to R.font.readex_regular, "/f/s.ttf" to R.font.readex_semibold, "/f/b.ttf" to R.font.readex_bold)
        private const val FAVICON = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 108 108'><rect width='108' height='108' rx='24' fill='#2F3BA8'/>" +
            "<path d='M54 27C70.6 27 84 38.4 84 52.5C84 66.6 70.6 78 54 78C50.7 78 47.5 77.5 44.6 76.7L33.6 82.6C32.2 83.4 30.6 82.1 31.1 80.6L34.3 70.6C28.2 66 24 59.6 24 52.5C24 38.4 37.4 27 54 27Z' fill='#fff'/>" +
            "<path d='M58.5 35L42.5 55.5H52.5L49 70L65.5 49H55.5Z' fill='#2F3BA8'/></svg>"
        private val PHONE = Regex("^\\+?\\d{5,15}$")
        private const val BACKLOG = 256
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_BODY = 16 * 1024
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 64
        private const val MAX_MESSAGE = 4_000
        private const val BURST_GAP_MS = 10 * 60 * 1000L
        private const val DEDUPE_WINDOW_MS = 10 * 60 * 1000L
        private const val DEDUPE_MAX = 50_000

        /** A new random API key: 32 hex characters, like SMS Sendr's. */
        fun newKey(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Constant-time comparison, so the key can't be guessed by timing. */
        private fun sameKey(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        private fun JSONObject.firstString(vararg names: String): String {
            for (n in names) {
                if (!has(n) || isNull(n)) continue
                val v = optString(n).trim()
                if (v.isNotEmpty()) return v
            }
            return ""
        }

        private fun result(success: Boolean, duplicate: Boolean, detail: String, error: String?, phone: String? = null): String =
            JSONObject()
                .put("success", success)
                .put("duplicate", duplicate)
                .put("detail", detail)
                .put("error", error ?: JSONObject.NULL)
                .apply { if (phone != null) put("phone", phone) }
                .toString()

        private fun reason(code: Int) = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            422 -> "Unprocessable Entity"
            else -> "Internal Server Error"
        }

        private fun iso(at: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(at))

        /** Loopback, private (10.x, 172.16–31.x, 192.168.x), link-local and IPv6 unique-local addresses. */
        fun isLocal(a: InetAddress?): Boolean {
            if (a == null) return false
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
            val b = a.address
            if (a is Inet4Address) return b[0].toInt() and 0xff == 100 && (b[1].toInt() and 0xc0) == 64 // 100.64/10
            if (a is Inet6Address) return (b[0].toInt() and 0xfe) == 0xfc
            return false
        }

        /** The phone's IPv4 address on Wi-Fi or its hotspot. */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.contains("ap") || it.name.startsWith("swlan")) 1 else 2 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()


    }
}
