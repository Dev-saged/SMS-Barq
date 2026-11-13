package dev.saged.barq.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.saged.barq.ui.theme.islandColor
import kotlinx.coroutines.flow.MutableSharedFlow

/** Room a tab screen leaves at the bottom while the floating bar is shown (0 when it is off or hidden). */
val LocalBottomBarInset = compositionLocalOf { 0.dp }

/** Height the floating bar takes above the system bar: 64dp capsule plus its 12dp margins. */
val PillNavHeight = 88.dp

/** Emits a tab's route when the user taps the tab that is already open (the list scrolls to its top). */
val LocalTabReselect = staticCompositionLocalOf<MutableSharedFlow<String>?> { null }

data class PillNavItem(
    val icon: ImageVector,
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/** Floating capsule navigation at the bottom, within thumb reach. The active item shows its label. */
@Composable
fun PillNav(items: List<PillNavItem>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .fillMaxWidth()
            .height(64.dp),
        shape = CircleShape,
        color = islandColor(),
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { PillNavButton(it) }
        }
    }
}

@Composable
private fun PillNavButton(item: PillNavItem) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val container by animateColorAsState(
        if (item.selected) scheme.primaryContainer else Color.Transparent, label = "navBg",
    )
    val tint = if (item.selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(container)
            .selectable(selected = item.selected, role = Role.Tab) {
                if (!item.selected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                item.onClick()
            }
            .semantics { contentDescription = item.label }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        AnimatedVisibility(
            visible = item.selected,
            enter = expandHorizontally() + fadeIn(),
            exit = shrinkHorizontally() + fadeOut(),
        ) {
            Text(
                item.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
