package dev.saged.barq.util

import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs suspending work from a BroadcastReceiver while keeping the process alive until it ends
 * (Android allows ~10 s; we stop at 9 s to stay safe).
 */
fun BroadcastReceiver.goAsync(block: suspend CoroutineScope.() -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            withTimeoutOrNull(9_000) { block() }
        } catch (_: Throwable) {
            // Never crash the receiver; the message is already stored before slow steps run.
        } finally {
            runCatching { pending.finish() }
        }
    }
}
