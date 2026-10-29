package dev.saged.barq.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saged.barq.R
import dev.saged.barq.data.contacts.ContactsRepository
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.ui.theme.LocalBarqColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun Category.icon(): ImageVector = when (this) {
    Category.OTP -> Icons.Outlined.VerifiedUser
    Category.BANK -> Icons.Outlined.AccountBalance
    Category.OFFERS -> Icons.Outlined.LocalOffer
    Category.SPAM -> Icons.Outlined.Block
    Category.PERSONAL -> Icons.Outlined.Person
}

@Composable
fun Category.label(): String = stringResource(
    when (this) {
        Category.OTP -> R.string.cat_otp
        Category.BANK -> R.string.cat_bank
        Category.OFFERS -> R.string.cat_offers
        Category.SPAM -> R.string.cat_spam
        Category.PERSONAL -> R.string.cat_personal
    }
)

/** Gives every screen access to the contacts index (names, photos). Set in MainActivity. */
val LocalContacts = staticCompositionLocalOf<ContactsRepository?> { null }

private val photoCache = LruCache<String, ImageBitmap>(150)

/**
 * Loads a contact photo (thumbnail) once and keeps it in memory. [version] is the contacts
 * version: Android keeps the same photo URI when a photo is replaced, so the cache key includes
 * it and a changed photo shows up right away.
 */
@Composable
fun rememberContactPhoto(uri: String?, version: Int = 0): ImageBitmap? {
    val context = LocalContext.current
    val key = uri?.let { "$it@$version" }
    val state = produceState(initialValue = key?.let { photoCache.get(it) }, key) {
        if (uri == null || key == null) {
            value = null
            return@produceState
        }
        photoCache.get(key)?.let {
            value = it
            return@produceState
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            }.getOrNull()
        }
        if (loaded != null) photoCache.put(key, loaded)
        value = loaded
    }
    return state.value
}

/** Current contacts version — recomposes callers when names or photos change. */
@Composable
fun contactsVersion(): Int {
    val contacts = LocalContacts.current ?: return 0
    val v by contacts.version.collectAsState()
    return v
}

/** Message text with saved contact names shown in place of their phone numbers. */
@Composable
fun rememberWithNames(text: String): String {
    val contacts = LocalContacts.current ?: return text
    val version = contactsVersion()
    return remember(text, version) { contacts.withNames(text) }
}

/** Contact photo when there is one; otherwise an initial letter, or a category icon for services. */
@Composable
fun Avatar(
    name: String,
    address: String,
    seed: Long,
    category: Category,
    size: Dp = 48.dp,
    photoUri: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val contacts = LocalContacts.current
    val version = contactsVersion()
    val uri = photoUri ?: remember(address, version) {
        PhoneUtils.splitAddresses(address).singleOrNull()?.let { contacts?.lookupCached(it)?.photoUri }
    }
    val photo = rememberContactPhoto(uri, version)
    val extra = LocalBarqColors.current
    val scheme = MaterialTheme.colorScheme
    val isService = !PhoneUtils.isReplyable(address) || category == Category.BANK || category == Category.OTP
    val colors = if (isService) extra.category(category, scheme) else extra.avatar(seed)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.container)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val letter = name.trim().firstOrNull { it.isLetter() }
        when {
            photo != null -> Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            isService || letter == null -> Icon(
                imageVector = if (isService) category.icon() else Icons.Outlined.Person,
                contentDescription = null,
                tint = colors.content,
                modifier = Modifier.size(size * 0.46f),
            )
            else -> Text(
                text = letter.uppercase(),
                color = colors.content,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.4f).sp,
            )
        }
    }
}

object TimeFormat {
    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    fun clock(millis: Long, s: AppSettings): String =
        SimpleDateFormat(if (s.use24h) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(millis))

    /** Short label for lists: time today, "أمس", weekday this week, otherwise the user's date pattern. */
    fun short(millis: Long, s: AppSettings, yesterday: String): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        if (sameDay(now, then)) return clock(millis, s)
        val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (sameDay(y, then)) return yesterday
        val diffDays = (now.timeInMillis - millis) / (24 * 60 * 60 * 1000L)
        if (diffDays < 6) return SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(millis))
        return SimpleDateFormat(s.datePattern, Locale.getDefault()).format(Date(millis))
    }

    fun dayHeader(millis: Long, s: AppSettings, today: String, yesterday: String): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        if (sameDay(now, then)) return today
        val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (sameDay(y, then)) return yesterday
        return SimpleDateFormat("EEEE، " + s.datePattern, Locale.getDefault()).format(Date(millis))
    }

    fun full(millis: Long, s: AppSettings): String =
        SimpleDateFormat(s.datePattern, Locale.getDefault()).format(Date(millis)) + " · " + clock(millis, s)

    fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return sameDay(ca, cb)
    }
}
