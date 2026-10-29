package dev.saged.barq.ui.thread

import android.content.Intent
import android.graphics.BitmapFactory
import android.telephony.SmsMessage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.saged.barq.R
import dev.saged.barq.data.db.Attachment
import dev.saged.barq.data.db.Box as MsgBox
import dev.saged.barq.data.db.DeliveryStatus
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.db.isIncoming
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.notifications.NotificationActionReceiver
import dev.saged.barq.ui.components.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val urlRegex = Regex("(?i)\\b((https?://|www\\.)[^\\s]+)")

@Composable
private fun linkify(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, linkColor) {
        buildAnnotatedString {
            var last = 0
            urlRegex.findAll(text).forEach { m ->
                append(text.substring(last, m.range.first))
                val raw = m.value.trimEnd('.', ',', ')', '،')
                val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
                withLink(
                    LinkAnnotation.Url(
                        url,
                        TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium))
                    )
                ) { append(raw) }
                last = m.range.first + raw.length
            }
            if (last < text.length) append(text.substring(last))
        }
    }
}

@Composable
fun MessageBubble(
    message: MessageEntity,
    settings: AppSettings,
    onLongPress: () -> Unit,
    onFailedClick: () -> Unit,
    onCancelScheduled: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val incoming = message.isIncoming
    val scheduled = message.box == MsgBox.QUEUED && message.scheduledAt > System.currentTimeMillis()
    val container = when {
        incoming -> scheme.surfaceContainerHigh
        scheduled -> scheme.surface
        message.box == MsgBox.FAILED -> scheme.errorContainer
        else -> scheme.primary
    }
    val content = when {
        incoming || scheduled -> scheme.onSurface
        message.box == MsgBox.FAILED -> scheme.onErrorContainer
        else -> scheme.onPrimary
    }
    // RTL: start = right side. Incoming on the start side, mine on the end side.
    val shape = if (incoming) RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    else RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalAlignment = if (incoming) Alignment.Start else Alignment.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(container)
                .then(if (scheduled) Modifier.border(1.5.dp, scheme.primary, shape) else Modifier)
                .combinedClickable(
                    onClick = { if (message.box == MsgBox.FAILED) onFailedClick() },
                    onLongClick = onLongPress,
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            message.attachments.forEach { AttachmentView(it) }
            if (message.body.isNotBlank()) {
                Text(
                    text = linkify(message.body),
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    color = content,
                )
            }
            if (incoming && message.otp != null) OtpStrip(message.otp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (message.autoReply) {
                    Icon(Icons.Outlined.SmartToy, null, Modifier.size(12.dp), tint = content.copy(alpha = 0.75f))
                }
                Text(
                    text = TimeFormat.clock(if (scheduled) message.scheduledAt else message.date, settings),
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.75f),
                )
                if (!incoming && !scheduled) StatusIcon(message, content.copy(alpha = 0.85f))
            }
        }
        if (!incoming) OutgoingFooter(message, scheduled, settings, onCancelScheduled)
    }
}

@Composable
private fun StatusIcon(message: MessageEntity, tint: androidx.compose.ui.graphics.Color) {
    val icon = when {
        message.box == MsgBox.FAILED -> Icons.Outlined.ErrorOutline
        message.box == MsgBox.QUEUED || message.box == MsgBox.OUTBOX -> Icons.Outlined.Schedule
        message.deliveryStatus == DeliveryStatus.DELIVERED -> Icons.Filled.DoneAll
        else -> Icons.Filled.Check
    }
    Icon(icon, null, Modifier.size(13.dp), tint = tint)
}

@Composable
private fun OutgoingFooter(message: MessageEntity, scheduled: Boolean, settings: AppSettings, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val parts = remember(message.body) {
        runCatching { SmsMessage.calculateLength(message.body, false)[0] }.getOrDefault(1)
    }
    val label = when {
        scheduled -> stringResource(R.string.scheduled_for, TimeFormat.full(message.scheduledAt, settings))
        message.box == MsgBox.FAILED -> stringResource(R.string.failed_tap_retry)
        message.box == MsgBox.QUEUED -> stringResource(R.string.status_queued)
        message.box == MsgBox.OUTBOX -> stringResource(R.string.status_sending)
        message.deliveryStatus == DeliveryStatus.DELIVERED -> stringResource(R.string.status_delivered)
        message.deliveryStatus == DeliveryStatus.FAILED -> stringResource(R.string.status_not_delivered)
        parts > 1 -> stringResource(R.string.status_sent_parts, parts)
        else -> null
    } ?: return
    Row(
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (message.box == MsgBox.FAILED) scheme.error else if (scheduled) scheme.primary else scheme.onSurfaceVariant,
            fontWeight = if (scheduled) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (scheduled) {
            Text(
                stringResource(R.string.cancel),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onCancel).padding(2.dp),
            )
        }
    }
}

@Composable
private fun OtpStrip(code: String) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var copied by remember(code) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.primaryContainer)
            .clickable {
                NotificationActionReceiver.copyToClipboard(context, code)
                copied = true
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            code,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
            color = scheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr),
        )
        Icon(
            if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
            contentDescription = stringResource(R.string.copy_code),
            tint = scheme.onPrimaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(if (copied) R.string.copied else R.string.copy),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun AttachmentView(attachment: Attachment) {
    val context = LocalContext.current
    val open = {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(attachment.path))
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        Unit
    }
    if (attachment.mime.startsWith("image/")) {
        val bitmap by produceState<ImageBitmap?>(null, attachment.path) {
            value = withContext(Dispatchers.IO) { decodeSampled(attachment.path, 720) }
        }
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = open),
            )
        } else {
            Box(Modifier.size(200.dp, 140.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
        }
    } else {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = open)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp))
            Text(attachment.name ?: attachment.mime, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

private fun decodeSampled(path: String, maxSide: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

@Composable
fun DayHeader(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
