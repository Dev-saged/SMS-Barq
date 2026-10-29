package dev.saged.barq.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.domain.NumberText
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.domain.TextNorm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A saved contact matched to a phone number. */
data class ContactInfo(
    val contactId: Long,
    val lookupKey: String?,
    val name: String,
    val photoUri: String?,
    val number: String,
)

/** One phone number of a contact, as shown in the contacts list (a contact with 3 numbers = 3 entries). */
@Immutable
data class ContactEntry(
    val contactId: Long,
    val name: String,
    val number: String,
    /** "Mobile", "Work"… in the phone's language, or null. */
    val typeLabel: String?,
    val photoUri: String?,
    /** Section letter: an Arabic or Latin letter, or "#". */
    val section: String,
    /** Folded name used for searching and sorting. */
    val searchName: String,
    val digits: String,
) {
    val key: String get() = "$contactId|$digits"
}

/**
 * Barq's own contacts index.
 *
 * Why not PhoneLookup: some phones (e.g. Samsung) compare full international numbers, so a
 * message from +972597857250 never matches a contact saved as 0597857250 or +970597857250.
 * Here every number is matched by its last 9 digits ([PhoneUtils.matchKey]), which works for
 * every prefix and every manufacturer.
 *
 * The same single read of the phone's contacts also produces the full, sorted contacts list used
 * by "New message" (all contacts, however many). A rebuild replaces the data in one step and only
 * bumps [version] when something really changed, so names and photos never flicker.
 */
@Singleton
class ContactsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private class Snapshot(val index: Map<String, ContactInfo>, val entries: List<ContactEntry>)

    @Volatile private var snapshot: Snapshot? = null
    private val lock = Any()

    /** Bumped whenever names or photos change so the UI can refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val _entries = MutableStateFlow<List<ContactEntry>>(emptyList())
    /** All phone numbers of all contacts, sorted (Arabic names first, then Latin, then the rest). */
    val entries: StateFlow<List<ContactEntry>> = _entries

    private var observerRegistered = false
    private var onChanged: (() -> Unit)? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun ensure(): Snapshot? {
        snapshot?.let { return it }
        if (!hasPermission()) return null // never cache "no contacts"
        synchronized(lock) {
            snapshot?.let { return it }
            val built = read()
            publish(built)
            return built
        }
    }

    private fun publish(s: Snapshot) {
        snapshot = s
        _entries.value = s.entries
        _version.value = _version.value + 1
    }

    /** Builds the index in the background (call from a worker thread). */
    fun warmUp() {
        ensure()
    }

    /**
     * Re-reads the phone's contacts (after an edit or a newly granted permission).
     * Returns true when names, numbers or photos actually changed.
     */
    fun rebuild(): Boolean {
        if (!hasPermission()) return false
        synchronized(lock) {
            val fresh = read()
            val old = snapshot
            if (old != null && old.index == fresh.index && old.entries == fresh.entries) return false
            publish(fresh)
            return true
        }
    }

    private fun read(): Snapshot {
        val map = HashMap<String, ContactInfo>(1024)
        val list = ArrayList<ContactEntry>(1024)
        val seen = HashSet<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
                // Primary numbers first so they win when two contacts share a number.
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(2)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    val number = c.getString(4).orEmpty()
                    val contactId = c.getLong(0)
                    val photo = c.getString(3)
                    val info = ContactInfo(
                        contactId = contactId,
                        lookupKey = c.getString(1),
                        name = name,
                        photoUri = photo,
                        number = number,
                    )
                    listOf(number, c.getString(5).orEmpty())
                        .filter { PhoneUtils.isPhoneNumber(it) }
                        .map { PhoneUtils.matchKey(it) }
                        .filter { it.length >= 7 }
                        .forEach { key -> map.putIfAbsent(key, info) }

                    if (!PhoneUtils.isPhoneNumber(number)) continue
                    val digits = PhoneUtils.normalizeDigits(number).filter { it.isDigit() }
                    // The same number saved twice (e.g. 059… and +970 59…) appears once.
                    if (!seen.add("$contactId|${PhoneUtils.matchKey(number)}")) continue
                    val type = c.getInt(6)
                    val label = runCatching {
                        ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, type, c.getString(7))
                            ?.toString()
                    }.getOrNull()
                    val folded = TextNorm.forName(name)
                    list += ContactEntry(
                        contactId = contactId,
                        name = name,
                        number = number,
                        typeLabel = label,
                        photoUri = photo,
                        section = sectionOf(folded),
                        searchName = folded,
                        digits = digits,
                    )
                }
            }
        }
        list.sortWith(compareBy<ContactEntry>({ groupOf(it.section) }, { it.section }, { it.searchName }, { it.number }))
        return Snapshot(map, list)
    }

    /** Full lookup — may build the index, so call it off the main thread. */
    fun lookup(address: String): ContactInfo? {
        if (address.isBlank() || !PhoneUtils.isPhoneNumber(address)) return null
        return ensure()?.index?.get(PhoneUtils.matchKey(address))
    }

    /** Main-thread-safe lookup: answers only from an index that is already built. */
    fun lookupCached(address: String): ContactInfo? {
        if (address.isBlank() || !PhoneUtils.isPhoneNumber(address)) return null
        return snapshot?.index?.get(PhoneUtils.matchKey(address))
    }

    fun nameFor(address: String): String? = lookup(address)?.name

    fun isContact(address: String): Boolean = lookup(address) != null

    /** Contact name(s) for a conversation; unknown numbers stay as they are. */
    fun displayName(addresses: String): String =
        PhoneUtils.splitAddresses(addresses).joinToString("، ") { nameFor(it) ?: it }

    /** Main-thread-safe name for one address (falls back to the address itself). */
    fun cachedName(address: String): String = lookupCached(address)?.name ?: address

    /** Numbers inside a message replaced by saved names (display only, safe on the main thread). */
    fun withNames(text: String): String =
        if (snapshot == null) text else NumberText.replaceWithNames(text) { lookupCached(it)?.name }

    /** Watches the phone's contacts; [onChange] runs (debounced by the caller) after any edit. */
    fun startWatching(onChange: () -> Unit) {
        onChanged = onChange
        if (observerRegistered || !hasPermission()) return
        runCatching {
            context.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI, true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        onChanged?.invoke()
                    }
                }
            )
            observerRegistered = true
        }
    }

    /**
     * Instant local search by name (Arabic letters folded: أ/إ/آ = ا, ة = ه, ى = ي) or by number
     * in any format (059…, +970…, +972…, Arabic digits).
     */
    suspend fun search(query: String): List<ContactEntry> = withContext(Dispatchers.Default) {
        ensure()
        val all = _entries.value
        val q = query.trim()
        if (q.isEmpty()) return@withContext all
        val name = TextNorm.forName(q)
        val qDigits = PhoneUtils.normalizeDigits(q).filter { it.isDigit() }
        val numberQuery = qDigits.length >= 3 && q.all { it.isDigit() || it in "+-() ." || it in '٠'..'٩' || it in '۰'..'۹' }
        val core = qDigits.trimStart('0').removePrefix("972").removePrefix("970")
        all.filter { e ->
            if (numberQuery) {
                e.digits.contains(qDigits) || (core.length >= 3 && e.digits.contains(core))
            } else {
                e.searchName.contains(name) ||
                    e.searchName.split(' ').any { it.startsWith(name) } ||
                    (qDigits.length >= 3 && e.digits.contains(qDigits))
            }
        }
    }

    companion object {
        /** Section letter of a folded name: its first Arabic/Latin letter, otherwise "#". */
        fun sectionOf(folded: String): String {
            val c = folded.firstOrNull { it.isLetterOrDigit() } ?: return "#"
            return when {
                c in 'ء'..'ي' -> c.toString()
                c in 'a'..'z' -> c.uppercaseChar().toString()
                c.isLetter() && c.code < 0x250 -> c.uppercaseChar().toString()
                else -> "#"
            }
        }

        /** Arabic sections first, then Latin, then "#". */
        fun groupOf(section: String): Int {
            val c = section.firstOrNull() ?: return 2
            return when {
                c in 'ء'..'ي' -> 0
                section == "#" -> 2
                else -> 1
            }
        }
    }
}
