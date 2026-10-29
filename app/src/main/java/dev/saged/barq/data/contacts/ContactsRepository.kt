package dev.saged.barq.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.domain.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class Contact(val name: String, val number: String, val photoUri: String?)

@Singleton
class ContactsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private data class Cached(val name: String?)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun clearCache() = cache.clear()

    /** Contact display name for a number, or null when unknown. Cached in memory. */
    fun nameFor(address: String): String? {
        if (address.isBlank() || !hasPermission() || !PhoneUtils.isPhoneNumber(address)) return null
        val key = PhoneUtils.normalize(address)
        cache[key]?.let { return it.name }
        val name = runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        cache[key] = Cached(name)
        return name
    }

    fun isContact(address: String): Boolean = nameFor(address) != null

    fun displayName(addresses: String): String =
        PhoneUtils.splitAddresses(addresses).joinToString("، ") { nameFor(it) ?: it }

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
                    if (seen.add(PhoneUtils.normalize(number))) result += Contact(name, number, c.getString(2))
                }
            }
        }
        result
    }
}
