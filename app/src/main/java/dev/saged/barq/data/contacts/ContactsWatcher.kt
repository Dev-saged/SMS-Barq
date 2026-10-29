package dev.saged.barq.data.contacts

import dev.saged.barq.data.MessageRepository
import dev.saged.barq.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps names and photos in sync with the phone's contacts: when you rename someone or change
 * their photo, Barq updates within a couple of seconds.
 */
@Singleton
class ContactsWatcher @Inject constructor(
    private val contacts: ContactsRepository,
    private val repository: dagger.Lazy<MessageRepository>,
    @AppScope private val scope: CoroutineScope,
) {
    private var pending: Job? = null

    /** Safe to call many times (e.g. again after the contacts permission is granted). */
    fun start() {
        scope.launch { contacts.warmUp() }
        contacts.startWatching {
            pending?.cancel()
            pending = scope.launch {
                delay(1_500) // contacts apps write in bursts
                repository.get().refreshNames()
            }
        }
    }
}
