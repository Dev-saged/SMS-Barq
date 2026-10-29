package dev.saged.barq.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.domain.NumberText
import dev.saged.barq.domain.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class Contact(val name: String, val number: String, val photoUri: String?)

/** A saved contact matched to a phone number. */
data class ContactInfo(
    val contactId: Long,
    val lookupKey: String?,
    val name: String,
    val photoUri: String?,
    val number: String,
)

/**
 * Barq's own contacts index.
 *
 * Why not PhoneLookup: some phones (e.g. Samsung) compare full international numbers, so a
 * message from +972597857250 never matches a contact saved as 0597857250 or +970597857250.
 * Here every number is matched by its last 9 digits ([PhoneUtils.matchKey]), which works for
 * every prefix and every manufacturer. The index is rebuilt automatically when contacts change,
 * and nothing is cached while the permission is missing.
 */
@Singleton
class ContactsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    @Volatile private var index: Map<String, ContactInfo>? = null
    private val lock = Any()

    /** Bumped whenever the index changes so the UI can refresh names and photos. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private var observerRegistered = false
    private var onChanged: (() -> Unit)? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun ensureIndex(): Map<String, ContactInfo> {
        index?.let { return it }
        if (!hasPermission()) return emptyMap() // never cache "no contacts"
        synchronized(lock) {
            index?.let { return it }
            val built = build()
            index = built
            _version.value = _version.value + 1
            return built
        }
    }

    private fun build(): Map<String, ContactInfo> {
        val map = HashMap<String, ContactInfo>(512)
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
                // Primary numbers first so they win when two contacts share a number.
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                    val number = c.getString(4).orEmpty()
                    val info = ContactInfo(
                        contactId = c.getLong(0),
                        lookupKey = c.getString(1),
                        name = name,
                        photoUri = c.getString(3),
                        number = number,
                    )
                    listOf(number, c.getString(5).orEmpty())
                        .filter { PhoneUtils.isPhoneNumber(it) }
                        .map { PhoneUtils.matchKey(it) }
                        .filter { it.length >= 7 }
                        .forEach { key -> map.putIfAbsent(key, info) }
                }
            }
        }
        return map
    }

    /** Builds the index in the background (call from a worker thread). */
    fun warmUp() {
        ensureIndex()
    }

    /** Drops the index; the next lookup rebuilds it with fresh names and photos. */
    fun invalidate() {
        index = null
        _version.value = _version.value + 1
    }

    /** Full lookup — may build the index, so call it off the main thread. */
    fun lookup(address: String): ContactInfo? {
        if (address.isBlank() || !PhoneUtils.isPhoneNumber(address)) return null
        return ensureIndex()[PhoneUtils.matchKey(address)]
    }

    /** Main-thread-safe lookup: answers only from an index that is already built. */
    fun lookupCached(address: String): ContactInfo? {
        if (address.isBlank() || !PhoneUtils.isPhoneNumber(address)) return null
        return index?.get(PhoneUtils.matchKey(address))
    }

    fun nameFor(address: String): String? = lookup(address)?.name

    fun isContact(address: String): Boolean = lookup(address) != null

    /** Contact name(s) for a conversation; unknown numbers stay as they are. */
    fun displayName(addresses: String): String =
        PhoneUtils.splitAddresses(addresses).joinToString("، ") { nameFor(it) ?: it }

    /** Numbers inside a message replaced by saved names (display only, safe on the main thread). */
    fun withNames(text: String): String =
        if (index == null) text else NumberText.replaceWithNames(text) { lookupCached(it)?.name }

    /** Watches the phone's contacts; [onChange] runs (debounced by the caller) after any edit. */
    fun startWatching(onChange: () -> Unit) {
        onChanged = onChange
        if (observerRegistered || !hasPermission()) return
        runCatching {
            context.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI, true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        invalidate()
                        onChanged?.invoke()
                    }
                }
            )
            observerRegistered = true
        }
    }

    suspend fun search(query: String, limit: Int = 30): List<Contact> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val result = ArrayList<Contact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
        )
        val uri = if (query.isBlank()) ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        else Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
        runCatching {
            context.contentResolver.query(
                uri, projection, null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { c ->
                val seen = HashSet<String>()
                while (c.moveToNext() && result.size < limit) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    if (seen.add(PhoneUtils.matchKey(number))) result += Contact(name, number, c.getString(2))
                }
            }
        }
        result
    }
}
