package dev.saged.barq.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.saged.barq.R
import dev.saged.barq.data.sim.SimInfo

/**
 * Two-SIM switch: "SIM 1 | SIM 2" with a pill that slides to the chosen side (and a light tick).
 * Shown only on phones with two active SIMs; the chosen SIM sends this message.
 */
@Composable
fun SimSwitch(sims: List<SimInfo>, subId: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (sims.size < 2) return
    val two = sims.sortedBy { it.slot }.take(2)
    val selected = two.indexOfFirst { it.subId == subId }.coerceAtLeast(0)
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.surfaceContainer)
            .padding(4.dp),
    ) {
        val half = maxWidth / 2
        val offset by animateDpAsState(
            half * selected,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "sim",
        )
        Box(
            Modifier
                .offset(x = offset)
                .width(half)
                .fillMaxHeight()
                .shadow(4.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(scheme.primary),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            two.forEachIndexed { i, sim ->
                val on = i == selected
                val color by animateColorAsState(if (on) scheme.onPrimary else scheme.onSurfaceVariant, label = "simText")
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (!on) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPick(sim.subId)
                            }
                        }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Outlined.SimCard, null, tint = color, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.sim_n, sim.slot + 1) + (sim.label.takeIf { it.isNotBlank() && !it.startsWith("SIM ") }?.let { " · $it" } ?: ""),
                        color = color,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}
