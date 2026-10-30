package dev.saged.barq.sms.gateway

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.saged.barq.data.db.BatchCounts
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.notifications.Notifier
import dev.saged.barq.sms.SendDrainer
import dev.saged.barq.util.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps Barq's local gateway running in the foreground (with one notification and a Stop button),
 * and sends what it receives: each accepted request wakes the sending engine right away, so a bot
 * can push a thousand messages and they are all queued at once and sent without waiting.
 * Wi-Fi and CPU locks keep the server reachable with the screen off.
 */
@AndroidEntryPoint
class GatewayService : Service() {

    @Inject lateinit var server: GatewayServer
    @Inject lateinit var drainer: SendDrainer
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var messages: MessageDao
    @Inject lateinit var notifier: Notifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The running gateway; cancelled when it is turned off. */
    private var session: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                settings.setGatewayEnabled(false)
                shutdown()
            }
            return START_NOT_STICKY
        }
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            else -> 0
        }
        val ok = runCatching {
            ServiceCompat.startForeground(this, Notifier.GATEWAY_ID, notifier.gateway(server.status.value, BatchCounts()), type)
        }.onFailure { CrashLog.warn("gateway-start", it) }.isSuccess
        if (!ok) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (session?.isActive != true) session = scope.launch { run() }
        return START_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run(): Unit = coroutineScope {
        val s = settings.current()
        if (!s.gatewayEnabled) return@coroutineScope shutdown()
        var key = s.gatewayKey
        if (key.isBlank()) {
            key = GatewayServer.newKey()
            settings.setGatewayKey(key)
        }
        val session = this
        if (!server.start(session, s.gatewayPort, key, s.gatewayDedupe)) {
            // Port in use or not allowed: the developer screen shows why.
            settings.setGatewayEnabled(false)
            return@coroutineScope shutdown()
        }
        acquireLocks()
        watchNetwork()

        // Turned off from the app.
        launch {
            settings.settings.map { it.gatewayEnabled }.first { !it }
            shutdown()
        }
        // Key / duplicate check / port changed while running.
        launch {
            settings.settings.map { Triple(it.gatewayPort, it.gatewayKey, it.gatewayDedupe) }
                .distinctUntilChanged().drop(1)
                .collect { (port, k, dedupe) ->
                    if (port != server.status.value.port) server.start(session, port, k, dedupe) else server.configure(k, dedupe)
                }
        }
        // One notification: address, how many came in, and the send progress of the current burst.
        launch {
            server.status
                .flatMapLatest { st ->
                    val counts = if (st.batchId != 0L) messages.observeBatch(st.batchId) else flowOf(BatchCounts())
                    counts.map { st to it }
                }
                .conflate()
                .collect { (st, counts) ->
                    notifier.updateGateway(st, counts)
                    delay(500)
                }
        }
        // Keep the CPU lock fresh for as long as the gateway runs.
        launch {
            while (true) {
                delay(WAKE_MS / 2)
                runCatching { wakeLock?.acquire(WAKE_MS) }
            }
        }
        // Send everything that arrives, right away.
        drainLoop()
    }

    /** Drains the queue each time requests arrive (signals are merged while a drain runs). */
    private suspend fun drainLoop() {
        for (signal in server.accepted) {
            try {
                while (true) {
                    if (drainer.drain() != SendDrainer.Result.BUSY) break
                    delay(400) // another sender is finishing a message; take over right after
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                CrashLog.warn("gateway-drain", t)
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        wakeLock = runCatching {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Barq:gateway")
                .apply { setReferenceCounted(false); acquire(WAKE_MS) }
        }.getOrNull()
        wifiLock = runCatching {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(mode, "Barq:gateway")
                .apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = server.refreshAddress()
            override fun onLost(network: Network) = server.refreshAddress()
        }
        if (runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess) networkCallback = cb
    }

    private fun shutdown() {
        server.stop()
        session?.cancel()
        session = null
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        server.stop()
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "dev.saged.barq.gateway.STOP"
        private const val WAKE_MS = 6 * 60 * 60 * 1000L

        /** Starts the gateway (when it is turned on in settings). False when Android refuses right now. */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
        }.onFailure { CrashLog.warn("gateway-start", it) }.isSuccess

        fun stopIntent(context: Context): Intent = Intent(context, GatewayService::class.java).setAction(ACTION_STOP)
    }
}
