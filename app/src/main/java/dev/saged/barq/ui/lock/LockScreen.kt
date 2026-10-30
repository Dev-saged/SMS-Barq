package dev.saged.barq.ui.lock

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saged.barq.R
import dev.saged.barq.data.settings.LockMethod

/**
 * Covers Barq while it is locked: brand colours (the same on every phone), the logo with a slow
 * glow, and one button that brings up the phone's fingerprint / face / PIN prompt. The prompt
 * also opens by itself as soon as the screen shows.
 */
@Composable
fun LockScreen(method: LockMethod, onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    val pulse = rememberInfiniteTransition(label = "lock")
    val glow by pulse.animateFloat(
        0.92f, 1.06f,
        infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF4F63E6), Color(0xFF2F3BA8), Color(0xFF121747)), Offset.Zero, Offset.Infinite))
            // Swallow every touch so nothing under the lock can be pressed.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(170.dp)
                        .scale(glow)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0x669AA8FF), Color.Transparent))),
                )
                Image(
                    painterResource(R.drawable.logo_mark),
                    null,
                    modifier = Modifier.size(104.dp).clip(RoundedCornerShape(30.dp)),
                )
            }
            Row2Lock()
            Text(stringResource(R.string.lock_title), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(if (method == LockMethod.DEVICE) R.string.lock_hint_pin else R.string.lock_hint),
                color = Color(0xFFC9D0FF),
                fontSize = 15.sp,
            )
        }
        Button(
            onClick = onUnlock,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 48.dp).height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1B2470)),
        ) {
            Icon(if (method == LockMethod.DEVICE) Icons.Outlined.Lock else Icons.Outlined.Fingerprint, null)
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.lock_open), fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun Row2Lock() {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Lock, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
}
