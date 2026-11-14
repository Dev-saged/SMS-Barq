package dev.saged.barq.ui.update

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.saged.barq.BuildConfig
import dev.saged.barq.R
import dev.saged.barq.data.update.UpdateManager
import dev.saged.barq.data.update.UpdateManager.State

private const val LRI = "⁦"
private const val PDI = "⁩"
private fun ltr(text: String) = "$LRI$text$PDI"

/** Update pill at the top of the screen (percentage ring) and the update sheet. */
@Composable
fun UpdateLayer(manager: UpdateManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    val sheet by manager.sheet.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { manager.autoCheck() }
    Box(Modifier.fillMaxSize()) {
        if (!sheet) {
            UpdatePill(
                state,
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 6.dp),
            ) {
                if (state is State.Ready) manager.install(context) else manager.showSheet(true)
            }
        }
    }
    if (sheet && state !is State.Idle) UpdateSheet(state, manager)
}

@Composable
private fun UpdatePill(state: State, modifier: Modifier, onClick: () -> Unit) {
    val label = when (state) {
        is State.Downloading -> stringResource(R.string.update_pill_downloading, state.percent)
        is State.Ready -> stringResource(R.string.update_pill_ready)
        is State.Installing -> stringResource(R.string.update_pill_installing)
        is State.Failed -> stringResource(R.string.update_pill_failed)
        else -> return
    }
    Surface(
        modifier = modifier.heightIn(min = 44.dp).clickable(onClick = onClick).semantics { contentDescription = label },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state) {
                is State.Downloading -> Ring(state.percent, 26)
                is State.Ready -> Icon(Icons.Outlined.CheckCircle, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                else -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            }
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
        }
    }
}

/** Circle that fills with the download percentage. */
@Composable
private fun Ring(percent: Int, size: Int) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val arc = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(size.dp)) {
        val w = 3.dp.toPx()
        val inset = w / 2
        val box = Size(this.size.width - w, this.size.height - w)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(w))
        drawArc(arc, -90f, 360f * percent / 100f, false, Offset(inset, inset), box, style = Stroke(w, cap = StrokeCap.Round))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateSheet(state: State, manager: UpdateManager) {
    val context = LocalContext.current
    val update = when (state) {
        is State.Available -> state.update
        is State.Downloading -> state.update
        is State.Ready -> state.update
        is State.Installing -> state.update
        is State.Failed -> state.update
        State.Idle -> return
    }
    ModalBottomSheet(
        onDismissRequest = { manager.showSheet(false) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.update_available_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.update_versions, ltr(update.version), ltr(BuildConfig.VERSION_NAME)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (update.notes.isNotBlank()) {
                Text(
                    stringResource(R.string.update_whats_new),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                )
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    Text(update.notes, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                when (state) {
                    is State.Downloading -> Progress(state.percent)
                    is State.Installing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                        Text(stringResource(R.string.update_pill_installing))
                    }
                    is State.Failed -> Text(
                        stringResource(R.string.update_download_failed, state.reason),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is State.Ready -> if (!context.packageManager.canRequestPackageInstalls()) {
                        Text(
                            stringResource(R.string.update_permission_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> Text(
                        stringResource(R.string.update_background_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val primary = when (state) {
                is State.Available -> R.string.update_download to { manager.start() }
                is State.Failed -> R.string.update_retry to { manager.start() }
                is State.Ready -> R.string.update_install to { manager.install(context) }
                else -> null
            }
            if (primary != null) {
                Button(
                    onClick = primary.second,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(stringResource(primary.first)) }
            }
            TextButton(
                onClick = { manager.showSheet(false) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(if (state is State.Downloading) R.string.update_hide else R.string.later)) }
        }
    }
}

@Composable
private fun Progress(percent: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Ring(percent, 44)
        Column {
            Text(stringResource(R.string.update_percent, percent), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
            Text(
                stringResource(R.string.update_background_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
