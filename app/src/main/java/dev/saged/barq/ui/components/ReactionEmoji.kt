package dev.saged.barq.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.saged.barq.R

/**
 * Reaction emoji drawn as Google's Noto emoji (Apache 2.0, see NOTICE.md), so they look glossy and
 * identical on every phone. Any other emoji falls back to the phone's own font.
 */
object ReactionEmoji {
    private val map: Map<String, Int> = mapOf(
        "❤" to R.drawable.emoji_heart,
        "👍" to R.drawable.emoji_thumbs_up,
        "😂" to R.drawable.emoji_joy,
        "😮" to R.drawable.emoji_wow,
        "😢" to R.drawable.emoji_cry,
        "🙏" to R.drawable.emoji_pray,
        "👎" to R.drawable.emoji_thumbs_down,
        "🔥" to R.drawable.emoji_fire,
        "😍" to R.drawable.emoji_love_eyes,
        "👏" to R.drawable.emoji_clap,
        "🎉" to R.drawable.emoji_party,
    )

    /** Drops the variation selector and skin tones, so "❤️" and "👍🏽" match too. */
    private fun key(emoji: String): String = buildString {
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            if (cp != 0xFE0F && cp !in 0x1F3FB..0x1F3FF) appendCodePoint(cp)
            i += Character.charCount(cp)
        }
    }

    @DrawableRes
    fun drawable(emoji: String): Int? = map[key(emoji)]
}

@Composable
fun Emoji(emoji: String, size: Dp, modifier: Modifier = Modifier) {
    val res = ReactionEmoji.drawable(emoji)
    if (res != null) {
        Image(painterResource(res), emoji, modifier = modifier.size(size))
    } else {
        Text(emoji, fontSize = (size.value * 0.82f).sp, modifier = modifier)
    }
}
