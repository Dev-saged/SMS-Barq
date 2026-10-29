package dev.saged.barq.data.telephony

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A row of content://sms. */
data class ProviderSms(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val type: Int,
    val read: Boolean,
    val status: Int,
    val subId: Int,
)

/**
 * Thin wrapper around the system SMS provider. Writing works only while Barq is the default
 * SMS app; every call is defensive so a vendor quirk never crashes the app.
 */
@Singleton
class TelephonyProvider @Inject constructor(@ApplicationContext private val context: Context) {

    private val resolver get() = context.contentResolver

    fun threadIdFor(addresses: Collection<String>): Long = runCatching {
        Telephony.Threads.getOrCreateThreadId(context, addresses.toSet())
    }.getOrElse {
        // Fallback keeps the app usable on ROMs that refuse thread creation.
        addresses.joinToString(",").hashCode().toLong() and 0x7fffffffL
    }

    /**
     * Streams SMS with _id greater than [afterId] (ascending) and hands them over in pages of
     * [pageSize], so even 100k messages never sit in memory at once.
     */
    suspend fun readSmsAfter(afterId: Long, pageSize: Int, onPage: suspend (List<ProviderSms>) -> Unit) {
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.READ,
            Telephony.Sms.STATUS, Telephony.Sms.SUBSCRIPTION_ID,
        )
        val cursor = runCatching {
            resolver.query(
                Telephony.Sms.CONTENT_URI, projection,
                "${Telephony.Sms._ID} > ?", arrayOf(afterId.toString()),
                "${Telephony.Sms._ID} ASC"
            )
        }.getOrNull() ?: return
        cursor.use { c ->
            val subIdx = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
            var page = ArrayList<ProviderSms>(pageSize)
            while (c.moveToNext()) {
                val type = c.getInt(6)
                // Drafts belong to the old app; we keep our own drafts.
                if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                page += ProviderSms(
                    id = c.getLong(0),
                    threadId = c.getLong(1),
                    address = c.getString(2).orEmpty(),
                    body = c.getString(3).orEmpty(),
                    date = c.getLong(4),
                    dateSent = c.getLong(5),
                    type = type,
                    read = c.getInt(7) == 1,
                    status = c.getInt(8),
                    subId = if (subIdx >= 0) c.getInt(subIdx) else -1,
                )
                if (page.size >= pageSize) {
                    onPage(page)
                    page = ArrayList(pageSize)
                }
            }
            if (page.isNotEmpty()) onPage(page)
        }
    }

    fun insertSms(
        address: String,
        body: String,
        date: Long,
        dateSent: Long,
        type: Int,
        read: Boolean,
        threadId: Long,
        subId: Int,
        status: Int = Telephony.Sms.STATUS_NONE,
    ): Long? = runCatching {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.DATE_SENT, dateSent)
            put(Telephony.Sms.TYPE, type)
            put(Telephony.Sms.READ, if (read) 1 else 0)
            put(Telephony.Sms.SEEN, if (read) 1 else 0)
            put(Telephony.Sms.THREAD_ID, threadId)
            put(Telephony.Sms.STATUS, status)
            if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        }
        resolver.insert(Telephony.Sms.CONTENT_URI, values)?.let { ContentUris.parseId(it) }
    }.getOrNull()

    fun updateType(providerId: Long, type: Int, errorCode: Int = 0) {
        runCatching {
            val values = ContentValues().apply {
                put(Telephony.Sms.TYPE, type)
                if (errorCode != 0) put(Telephony.Sms.ERROR_CODE, errorCode)
            }
            resolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId), values, null, null)
        }
    }

    fun updateStatus(providerId: Long, status: Int) {
        runCatching {
            val values = ContentValues().apply { put(Telephony.Sms.STATUS, status) }
            resolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId), values, null, null)
        }
    }

    fun markRead(providerIds: List<Long>) {
        if (providerIds.isEmpty()) return
        runCatching {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            providerIds.chunked(500).forEach { chunk ->
                resolver.update(
                    Telephony.Sms.CONTENT_URI, values,
                    "${Telephony.Sms._ID} IN (${chunk.joinToString(",")})", null
                )
            }
        }
    }

    fun deleteSms(providerIds: List<Long>) {
        if (providerIds.isEmpty()) return
        runCatching {
            providerIds.chunked(500).forEach { chunk ->
                resolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID} IN (${chunk.joinToString(",")})", null)
            }
        }
    }
}
