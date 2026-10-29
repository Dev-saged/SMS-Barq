package dev.saged.barq.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saged.barq.R
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.ui.theme.LocalBarqColors
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

/** Initial letter for people, a category icon for service senders. */
@Composable
fun Avatar(name: String, address: String, seed: Long, category: Category, size: Dp = 48.dp) {
    val extra = LocalBarqColors.current
    val scheme = MaterialTheme.colorScheme
    val isService = !PhoneUtils.isReplyable(address) || category == Category.BANK || category == Category.OTP
    val colors = if (isService) extra.category(category, scheme) else extra.avatar(seed)
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(colors.container),
        contentAlignment = Alignment.Center,
    ) {
        val letter = name.trim().firstOrNull { it.isLetter() }
        if (isService || letter == null) {
            Icon(
                imageVector = if (isService) category.icon() else Icons.Outlined.Person,
                contentDescription = null,
                tint = colors.content,
                modifier = Modifier.size(size * 0.46f),
            )
        } else {
            Text(
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
