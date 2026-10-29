package dev.saged.barq.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.saged.barq.R
import dev.saged.barq.data.db.Box as MsgBox
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.ConversationEntity
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.ui.theme.LocalBarqColors

@Composable
fun ConversationRow(
    conversation: ConversationEntity,
    settings: AppSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val unread = conversation.unreadCount > 0
    val bg by animateColorAsState(if (selected) scheme.secondaryContainer else scheme.background, label = "rowBg")
    val yesterday = stringResource(R.string.yesterday)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box {
            Avatar(
                name = conversation.displayName,
                address = conversation.addresses,
                seed = conversation.threadId,
                category = conversation.category,
            )
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle, contentDescription = null, tint = scheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(scheme.background),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = conversation.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (conversation.category != Category.PERSONAL) CategoryTag(conversation.category)
                if (conversation.muted) {
                    Icon(Icons.Outlined.NotificationsOff, null, Modifier.size(15.dp), tint = scheme.onSurfaceVariant)
                }
                Box(Modifier.weight(1f, fill = true))
                Text(
                    text = TimeFormat.short(conversation.date, settings, yesterday),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (unread) scheme.primary else scheme.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val draft = conversation.draft
                val failed = conversation.lastBox == MsgBox.FAILED
                if (failed) {
                    Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = scheme.error)
                }
                Text(
                    text = when {
                        draft != null -> stringResource(R.string.draft_prefix, draft)
                        failed -> stringResource(R.string.send_failed_short)
                        conversation.lastFromMe -> stringResource(R.string.you_prefix, conversation.snippet)
                        else -> conversation.snippet
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        draft != null || failed -> scheme.error
                        unread -> scheme.onSurface
                        else -> scheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.pinned) {
                    Icon(Icons.Filled.PushPin, null, Modifier.size(15.dp), tint = scheme.onSurfaceVariant)
                }
                if (unread) UnreadBadge(conversation.unreadCount)
            }
        }
    }
}

@Composable
fun CategoryTag(category: Category) {
    val colors = LocalBarqColors.current.category(category, MaterialTheme.colorScheme)
    Text(
        text = category.label(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.content,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.container)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .widthIn(min = 22.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}
