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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saged.barq.BuildConfig
import dev.saged.barq.R

private val Night = Color(0xFF121747)
private val Indigo = Color(0xFF2F3BA8)
private val Bright = Color(0xFF4F63E6)
private val Glow = Color(0xFF9AA8FF)
private val NameTint = Color(0xFFC9D0FF)
private val Muted = Color(0xFFB8BEEA)

private data class Social(@DrawableRes val icon: Int, val label: Int, val url: String)

private val socials = listOf(
    Social(R.drawable.ic_social_whatsapp, R.string.social_whatsapp, "https://wa.me/972597857250"),
    Social(R.drawable.ic_social_instagram, R.string.social_instagram, "https://instagram.com/sajedd.0"),
    Social(R.drawable.ic_social_facebook, R.string.social_facebook, "https://facebook.com/saged.t"),
    Social(R.drawable.ic_social_x, R.string.social_x, "https://x.com/saged_al"),
    Social(R.drawable.ic_social_github, R.string.social_github, "https://github.com/Dev-saged"),
)

/**
 * The developer card at the bottom of Settings: who made Barq, how to reach him, where it was
 * made, and the version. Brand colours (not Material You) so it looks the same on every phone.
 */
@Composable
fun AboutCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .shadow(18.dp, RoundedCornerShape(30.dp), ambientColor = Night, spotColor = Indigo)
            .clip(RoundedCornerShape(30.dp))
            .background(Brush.linearGradient(listOf(Bright, Indigo, Night), start = Offset.Zero, end = Offset.Infinite))
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        listOf(Glow.copy(alpha = 0.38f), Color.Transparent),
                        center = Offset(size.width * 0.18f, size.height * 0.12f),
                        radius = size.width * 0.75f,
                    ),
                    radius = size.width * 0.75f,
                    center = Offset(size.width * 0.18f, size.height * 0.12f),
                )
            }
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(30.dp))
            .padding(horizontal = 20.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.about_design_dev),
            color = Muted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.about_name),
            color = NameTint,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        StarDivider()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.about_contact), color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            socials.forEach { SocialButton(it) }
        }
        Spacer(Modifier.height(22.dp))
        MadeInGaza()
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.about_rights),
            color = Muted.copy(alpha = 0.85f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "v" + BuildConfig.VERSION_NAME,
            color = NameTint,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun StarDivider() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.width(80.dp).height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, NameTint.copy(alpha = 0.7f))))
        )
        Icon(painterResource(R.drawable.ic_star), null, tint = NameTint, modifier = Modifier.size(16.dp))
        Box(
            Modifier.width(80.dp).height(1.dp)
                .background(Brush.horizontalGradient(listOf(NameTint.copy(alpha = 0.7f), Color.Transparent)))
        )
    }
}

@Composable
private fun SocialButton(social: Social) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "press")
    val label = stringResource(social.label)
    Box(
        modifier = Modifier
            .size(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)), RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = ripple(color = Color.White)) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(social.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(social.icon), contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun MadeInGaza() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.22f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
            .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painterResource(R.drawable.ic_flag_ps), null,
            modifier = Modifier.width(30.dp).height(20.dp).clip(RoundedCornerShape(4.dp)),
        )
        Text(
            stringResource(R.string.made_in_gaza),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            painterResource(R.drawable.ic_heart), null,
            tint = Color(0xFFFF5A82),
            modifier = Modifier
                .size(20.dp)
                .shadow(8.dp, RoundedCornerShape(50), ambientColor = Color(0xFFFF5A82), spotColor = Color(0xFFFF5A82)),
        )
    }
}
