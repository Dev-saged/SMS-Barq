package dev.saged.barq.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

private val BarWidth = 26.dp
private val BubbleSize = 68.dp

/**
 * Side bar of letters for long contact lists. Touch or drag on it to jump to a letter:
 *  - a large letter bubble follows the finger and pulses when the letter changes,
 *  - letters near the finger grow in a wave (like a dock) and settle back when released,
 *  - a light haptic tick on every new letter.
 * Placed on the physical left edge (the free side in a right-to-left list).
 */
@Composable
fun AlphabetBar(
    letters: List<String>,
    onLetter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (letters.size < 2) return
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pick by rememberUpdatedState(onLetter)

    var active by remember { mutableStateOf(false) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var current by remember { mutableIntStateOf(-1) }
    var barHeight by remember { mutableIntStateOf(1) }
    val count = letters.size

    fun update(y: Float) {
        fingerY = y.coerceIn(0f, barHeight.toFloat())
        val index = ((fingerY / barHeight) * count).toInt().coerceIn(0, count - 1)
        if (index != current) {
            current = index
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            pick(letters[index])
        }
    }

    val barAlpha by animateFloatAsState(if (active) 1f else 0f, tween(180), label = "barBg")
    val bubbleScale by animateFloatAsState(
        if (active) 1f else 0f,
        spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "bubble",
    )
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(current, active) {
        if (active && current >= 0) {
            pulse.snapTo(1.18f)
            pulse.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium))
        }
    }

    Box(modifier.fillMaxHeight().width(BarWidth)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(scheme.surfaceContainerHigh.copy(alpha = 0.92f * barAlpha))
                .onSizeChanged { barHeight = it.height.coerceAtLeast(1) }
                .pointerInput(count) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        active = true
                        current = -1
                        update(down.position.y)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            update(change.position.y)
                            change.consume()
                        }
                        active = false
                    }
                },
        ) {
            val slot = barHeight.toFloat() / count
            val focus = if (active) fingerY / slot - 0.5f else -100f
            letters.forEachIndexed { i, letter ->
                val distance = abs(i - focus)
                val target = if (active && distance < 3.2f) 1f + 0.95f * (1f - distance / 3.2f) else 1f
                val scale by animateFloatAsState(
                    target,
                    spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
                    label = "letter$i",
                )
                val shift = with(density) { 16.dp.toPx() } * (scale - 1f)
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = letter,
                        fontSize = 10.sp,
                        fontWeight = if (i == current && active) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (i == current && active) scheme.primary else scheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = shift
                        },
                    )
                }
            }
        }

        if (bubbleScale > 0.01f && current in letters.indices) {
            val sizePx = with(density) { BubbleSize.toPx() }
            val gapPx = with(density) { (BarWidth + 22.dp).toPx() }
            Box(
                modifier = Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .absoluteOffsetPx(gapPx.roundToInt(), (fingerY - sizePx / 2).roundToInt())
                    .size(BubbleSize)
                    .graphicsLayer {
                        val s = bubbleScale * pulse.value
                        scaleX = s
                        scaleY = s
                        alpha = bubbleScale.coerceIn(0f, 1f)
                    }
                    .shadow(10.dp, CircleShape)
                    .clip(CircleShape)
                    .background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letters[current],
                    color = scheme.onPrimary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun Modifier.absoluteOffsetPx(x: Int, y: Int): Modifier = this.absoluteOffset { IntOffset(x, y) }
