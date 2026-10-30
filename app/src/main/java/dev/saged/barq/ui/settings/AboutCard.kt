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
 * The developer card at the bottom of Settings: compact and tidy — who made Barq, how to reach
 * him, where it was made, and the version. Brand colours (not Material You) so it looks the same
 * on every phone.
 */
@Composable
fun AboutCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .shadow(10.dp, RoundedCornerShape(22.dp), ambientColor = Night, spotColor = Indigo)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Bright, Indigo, Night), start = Offset.Zero, end = Offset.Infinite))
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        listOf(Glow.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(size.width * 0.15f, 0f),
                        radius = size.width * 0.6f,
                    ),
                    radius = size.width * 0.6f,
                    center = Offset(size.width * 0.15f, 0f),
                )
            }
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Who: small label above a medium name, version on the other side.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.about_design_dev), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.about_name), color = NameTint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "v" + BuildConfig.VERSION_NAME,
                color = Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        // How to reach him: one row of small buttons.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            socials.forEach { SocialButton(it, Modifier.weight(1f)) }
        }
        // Where: a thin line with the flag, then the rights.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painterResource(R.drawable.ic_flag_ps), null,
                modifier = Modifier.width(21.dp).height(14.dp).clip(RoundedCornerShape(3.dp)),
            )
            Text(
                stringResource(R.string.made_in_gaza),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(painterResource(R.drawable.ic_heart), null, tint = Color(0xFFFF5A82), modifier = Modifier.size(14.dp))
        }
        Text(
            stringResource(R.string.about_rights),
            color = Muted.copy(alpha = 0.8f),
            fontSize = 10.sp,
            textAlign = TextAlign.Start,
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
    Box(
        modifier = modifier
            .height(38.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = ripple(color = Color.White)) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(social.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(social.icon), contentDescription = label, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}
