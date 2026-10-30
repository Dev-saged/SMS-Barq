package dev.saged.barq.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saged.barq.BuildConfig
import dev.saged.barq.R

private data class Social(@DrawableRes val icon: Int, val label: Int, val url: String)

private val socials = listOf(
    Social(R.drawable.ic_social_whatsapp, R.string.social_whatsapp, "https://wa.me/972597857250"),
    Social(R.drawable.ic_social_instagram, R.string.social_instagram, "https://instagram.com/sajedd.0"),
    Social(R.drawable.ic_social_facebook, R.string.social_facebook, "https://facebook.com/saged.t"),
    Social(R.drawable.ic_social_x, R.string.social_x, "https://x.com/saged_al"),
    Social(R.drawable.ic_social_github, R.string.social_github, "https://github.com/Dev-saged"),
)

/**
 * Who made Barq, at the very end of Settings: centred, on the page itself (no coloured box), in
 * the app's own colours so it matches the rest of the screen in light and dark mode.
 */
@Composable
fun AboutCard(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // A short hairline separates it from the last settings group.
        Box(
            Modifier
                .width(40.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(scheme.outlineVariant)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.about_design_dev),
                color = scheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(R.string.about_name),
                color = scheme.primary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            socials.forEach { SocialButton(it) }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Image(
                painterResource(R.drawable.ic_flag_ps), null,
                modifier = Modifier.width(18.dp).height(12.dp).clip(RoundedCornerShape(2.dp)),
            )
            Text(
                stringResource(R.string.made_in_gaza),
                color = scheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(painterResource(R.drawable.ic_heart), null, tint = Color(0xFFE5486D), modifier = Modifier.size(12.dp))
        }
        Text(
            stringResource(R.string.about_rights) + "  ·  v" + BuildConfig.VERSION_NAME,
            color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SocialButton(social: Social, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "press")
    val label = stringResource(social.label)
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh)
            .border(BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)), CircleShape)
            .clickable(interactionSource = interaction, indication = ripple(color = scheme.primary)) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(social.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(social.icon), contentDescription = label, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
