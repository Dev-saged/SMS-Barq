package dev.saged.barq.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.saged.barq.R
import dev.saged.barq.data.db.Box as MsgBox
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.ConversationEntity
import dev.saged.barq.data.db.sortTime
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.ui.theme.LocalBarqColors

private val AvatarSize = 52.dp
private val RowPadding = 16.dp
private val AvatarGap = 14.dp

/** Fixed width for the time/badge column so every row lines up, whatever the text length. */
private val TrailingWidth = 56.dp

/**
 * One conversation in a list. Layout is fixed-column:
 *   [avatar] [name ……………… | time ]
 *            [last message … | badge]
 * followed by a thin divider that starts after the avatar.
 */
@Composable
fun ConversationRow(
    conversation: ConversationEntity,
    settings: AppSettings,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAvatarClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.background,
    divider: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val unread = conversation.unreadCount > 0
    val bg by animateColorAsState(if (selected) scheme.secondaryContainer else container, label = "rowBg")
    val yesterday = stringResource(R.string.yesterday)
    val draft = conversation.draft
    val failed = conversation.lastBox == MsgBox.FAILED
    val pending = conversation.lastBox == MsgBox.QUEUED || conversation.lastBox == MsgBox.OUTBOX
    val snippet = rememberWithNames(conversation.snippet)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Avatar(
                    name = conversation.displayName,
                    address = conversation.addresses,
                    seed = conversation.threadId,
                    category = conversation.category,
                    size = AvatarSize,
                    onClick = onAvatarClick,
                    modifier = Modifier.sharedAvatar(conversation.threadId),
                )
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle, contentDescription = null, tint = scheme.primary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(container),
                    )
                }
            }
            Spacer(Modifier.width(AvatarGap))
            Column(Modifier.weight(1f)) {
                // Line 1: name (+ small tags) | time
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
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
                    }
                    Text(
                        text = TimeFormat.short(conversation.sortTime, settings, yesterday),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (unread) scheme.primary else scheme.onSurfaceVariant,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(min = TrailingWidth).padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Line 2: last message | unread badge / pin
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (failed && draft == null) {
                        Icon(
                            Icons.Outlined.ErrorOutline, null, Modifier.padding(end = 6.dp).size(16.dp),
                            tint = scheme.error,
                        )
                    } else if (pending && draft == null) {
                        Icon(
                            Icons.Outlined.Schedule, null, Modifier.padding(end = 6.dp).size(15.dp),
                            tint = scheme.primary,
                        )
                    }
                    Text(
                        text = when {
                            draft != null -> stringResource(R.string.draft_prefix, draft)
                            failed -> stringResource(R.string.send_failed_short)
                            conversation.lastFromMe -> stringResource(R.string.you_prefix, snippet)
                            else -> snippet
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            draft != null || failed -> scheme.error
                            unread -> scheme.onSurface
                            else -> scheme.onSurfaceVariant
                        },
                        fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier.widthIn(min = TrailingWidth).padding(start = 8.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        when {
                            unread -> UnreadBadge(conversation.unreadCount)
                            conversation.pinned -> Icon(
                                Icons.Filled.PushPin, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = RowPadding + AvatarSize + AvatarGap),
                thickness = 0.6.dp,
                color = scheme.outlineVariant.copy(alpha = 0.55f),
            )
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
