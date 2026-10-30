package dev.saged.barq.data.contacts

import android.content.Context
import android.provider.ContactsContract
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.domain.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A contact group made in the phone's Contacts app (Family, Work, Customers…). */
@Immutable
data class PhoneGroup(
    /** Several accounts can have a group with the same title: they are shown as one. */
    val ids: List<Long>,
    val title: String,
    /** Contacts in the group that have a phone number. */
    val members: Int,
)

/**
 * Reads the groups of the phone's Contacts app, so "New message" can send to a whole group in one
 * tap. Each person gets one number (the primary one, else the first mobile, else the first) and
 * their own separate SMS in their own conversation.
 */
@Singleton
class PhoneGroups @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contacts: ContactsRepository,
) {
    /** Groups that have at least one contact with a phone number, sorted by title. */
    suspend fun groups(): List<PhoneGroup> = withContext(Dispatchers.IO) {
        if (!contacts.hasPermission()) return@withContext emptyList()
        contacts.warmUp()
        val withPhone = contacts.entries.value.mapTo(HashSet()) { it.contactId }
        val titles = HashMap<Long, String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(
                    ContactsContract.Groups._ID, ContactsContract.Groups.TITLE,
                    ContactsContract.Groups.SYSTEM_ID, ContactsContract.Groups.DELETED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(3) == 1) continue
                    val title = c.getString(1)?.trim().orEmpty()
                    // "My contacts" is everybody: not a real group.
                    val system = c.getString(2).orEmpty()
                    if (title.isEmpty() || system.equals("Contacts", ignoreCase = true)) continue
                    titles[c.getLong(0)] = title
                }
            }
        }
        if (titles.isEmpty()) return@withContext emptyList()
        val membersByGroup = members(titles.keys)
        titles.entries
            .groupBy({ it.value }, { it.key })
            .mapNotNull { (title, ids) ->
                val people = ids.flatMapTo(HashSet()) { membersByGroup[it].orEmpty() }.filter { it in withPhone }
                if (people.isEmpty()) null else PhoneGroup(ids, title, people.size)
            }
            .sortedBy { it.title.lowercase() }
    }

    /** One number per person of the group, with the person's name. */
    suspend fun numbers(group: PhoneGroup): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val people = members(group.ids.toSet()).values.flatten().toHashSet()
        val byContact = contacts.entries.value.filter { it.contactId in people }.groupBy { it.contactId }
        byContact.values.mapNotNull { numbers ->
            val best = numbers.firstOrNull { it.typeLabel != null && isMobileLabel(it.typeLabel) } ?: numbers.firstOrNull()
            best?.let { it.number to it.name }
        }.distinctBy { PhoneUtils.matchKey(it.first) }
    }

    private fun isMobileLabel(label: String): Boolean {
        val l = label.lowercase()
        return l.contains("mobile") || l.contains("cell") || l.contains("جوال") || l.contains("محمول")
    }

    private fun members(groupIds: Set<Long>): Map<Long, List<Long>> {
        val out = HashMap<Long, MutableList<Long>>()
        if (groupIds.isEmpty()) return out
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID,
                    ContactsContract.Data.CONTACT_ID,
                ),
                "${ContactsContract.Data.MIMETYPE} = ? AND " +
                    "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} IN (${groupIds.joinToString(",")})",
                arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
                null,
            )?.use { c ->
                while (c.moveToNext()) out.getOrPut(c.getLong(0)) { ArrayList() } += c.getLong(1)
            }
        }
        return out
    }
}
