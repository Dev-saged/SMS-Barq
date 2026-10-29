package dev.saged.barq.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One lock for everything that writes to the system SMS provider or decides which conversation
 * a number belongs to (incoming, outgoing, import, sync, merge). It is the reason a message is
 * never imported twice and a number never ends up in two conversations.
 */
@Singleton
class ProviderLock @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
