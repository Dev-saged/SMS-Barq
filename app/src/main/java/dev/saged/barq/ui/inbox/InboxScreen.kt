package dev.saged.barq.ui.inbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import dev.saged.barq.R
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.ConversationEntity
import dev.saged.barq.data.db.done
import dev.saged.barq.data.db.BatchCounts
import dev.saged.barq.data.db.finished
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.db.SearchHit
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SwipeAction
import dev.saged.barq.domain.TextNorm
import dev.saged.barq.notifications.NotificationActionReceiver
import dev.saged.barq.ui.MainViewModel
import dev.saged.barq.ui.components.Avatar
import dev.saged.barq.ui.components.ConversationRow
import dev.saged.barq.ui.components.TimeFormat
import dev.saged.barq.ui.components.label
import dev.saged.barq.ui.components.rememberContactName
import dev.saged.barq.ui.components.rememberDefaultSmsRequest
import kotlinx.coroutines.launch

@Composable
fun InboxScreen(
    mainViewModel: MainViewModel,
    openThread: (Long) -> Unit,
    openThreadAt: (threadId: Long, messageId: Long) -> Unit,
    newMessage: () -> Unit,
    openArchive: () -> Unit,
    openBin: () -> Unit,
    openSettings: () -> Unit,
    openContact: (Long) -> Unit,
    openCrashLog: () -> Unit,
    openBatch: (Long) -> Unit,
    openExternal: (Long) -> Unit,
    openSendLimit: () -> Unit,
    vm: InboxViewModel = hiltViewModel(),
) {
    val pagingItems = vm.conversationsPaged.collectAsLazyPagingItems()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val otp by vm.freshOtp.collectAsStateWithLifecycle()
    val spamCount by vm.spamCount.collectAsStateWithLifecycle()
    val hits by vm.messageHits.collectAsStateWithLifecycle()
    val bulk by vm.bulk.collectAsStateWithLifecycle()
    val external by vm.external.collectAsStateWithLifecycle()
    val isDefault by mainViewModel.isDefault.collectAsStateWithLifecycle()
    val sync by mainViewModel.syncProgress.collectAsStateWithLifecycle()
    val crashPending by mainViewModel.crashPending.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val expandedFab by remember { derivedStateOf { listState.firstVisibleItemIndex < 2 } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val requestDefault = rememberDefaultSmsRequest { mainViewModel.onPermissionsChanged() }
    val archivedMsg = stringResource(R.string.archived_done)
    val deletedMsg = stringResource(R.string.deleted_done)
    val undo = stringResource(R.string.undo)
    val searching = query.isNotBlank()

    fun onSwipe(action: SwipeAction, c: ConversationEntity) {
        vm.swipe(action, c)
        val msg = when (action) {
            SwipeAction.ARCHIVE -> archivedMsg
            SwipeAction.DELETE -> if (settings.recycleBin) deletedMsg else null
            else -> null
        } ?: return
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(msg, undo, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) {
                if (action == SwipeAction.ARCHIVE) vm.undoArchive(c.threadId) else vm.undoDelete(c.threadId)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selection.isEmpty()) {
                InboxTopBar(openArchive, openBin, openSettings)
            } else {
                // The buttons follow what is selected: all pinned → "unpin", all muted → "unmute".
                val chosen = pagingItems.itemSnapshotList.items.filter { it.threadId in selection }
                val allPinned = chosen.isNotEmpty() && chosen.all { it.pinned }
                val allMuted = chosen.isNotEmpty() && chosen.all { it.muted }
                SelectionTopBar(
                    count = selection.size,
                    allPinned = allPinned,
                    allMuted = allMuted,
                    onClose = vm::clearSelection,
                    onPin = { vm.pinSelected(!allPinned) },
                    onMute = { vm.muteSelected(!allMuted) },
                    onArchive = vm::archiveSelected,
                    onRead = vm::readSelected,
                    onDelete = vm::deleteSelected,
                    onBlock = vm::blockSelected,
                )
            }
        },
        floatingActionButton = {
            if (selection.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = newMessage,
                    expanded = expandedFab,
                    icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    text = { Text(stringResource(R.string.new_message)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(18.dp),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            if (!isDefault) {
                item(key = "default", contentType = "card") {
                    InfoCard(
                        icon = Icons.Outlined.Sms,
                        text = stringResource(R.string.not_default_banner),
                        action = stringResource(R.string.make_default),
                        onAction = requestDefault,
                    )
                }
            }
            if (crashPending) {
                item(key = "crash", contentType = "card") {
                    InfoCard(
                        icon = Icons.Outlined.BugReport,
                        text = stringResource(R.string.crash_banner),
                        action = stringResource(R.string.show),
                        onAction = {
                            mainViewModel.dismissCrash()
                            openCrashLog()
                        },
                    )
                }
            }
            item(key = "search", contentType = "search") { SearchField(query) { vm.query.value = it } }
            if (!searching) {
                item(key = "chips", contentType = "chips") { FilterChips(filter) { vm.filter.value = it } }
            }
            bulk?.let { b ->
                item(key = "bulk", contentType = "card") {
                    BulkCard(
                        ui = b,
                        onPause = { vm.pauseBulk(b.batchId) },
                        onResume = vm::resumeBulk,
                        onCancel = { vm.cancelBulk(b.batchId) },
                        onResend = { vm.resendFailed(b.batchId) },
                        onClose = { vm.dismissBulk() },
                        onDetails = { openBatch(b.batchId) },
                        onLimit = openSendLimit,
                    )
                }
            }
            external?.let { e ->
                if (!searching) {
                    item(key = "external", contentType = "card") {
                        ExternalCard(
                            ui = e,
                            onDetails = { openExternal(e.since) },
                            onResend = { vm.resendExternal(e.since) },
                            onClose = { vm.dismissExternal() },
                            onLimit = openSendLimit,
                        )
                    }
                }
            }
            item(key = "otp", contentType = "card") {
                AnimatedVisibility(
                    visible = otp != null && filter == null && !searching,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    otp?.let { m -> OtpCard(m, onCopied = { vm.onOtpCopied(m) }) { vm.dismissOtp(m.id) } }
                }
            }
            if (sync.running && sync.imported > 0) {
                item(key = "sync", contentType = "text") {
                    Text(
                        stringResource(R.string.importing, sync.imported),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            if (searching && pagingItems.itemCount > 0) {
                item(key = "h_conv", contentType = "header") { ListHeader(stringResource(R.string.search_conversations)) }
            }
            items(
                count = pagingItems.itemCount,
                key = pagingItems.itemKey { it.threadId },
                contentType = pagingItems.itemContentType { "conversation" },
            ) { index ->
                val c = pagingItems[index] ?: return@items
                SwipeableRow(
                    conversation = c,
                    settings = settings,
                    enabled = selection.isEmpty() && !searching,
                    onSwipe = ::onSwipe,
                ) {
                    ConversationRow(
                        conversation = c,
                        settings = settings,
                        selected = c.threadId in selection,
                        onClick = {
                            if (selection.isNotEmpty()) vm.toggleSelection(c.threadId) else openThread(c.threadId)
                        },
                        onLongClick = { vm.toggleSelection(c.threadId) },
                        onAvatarClick = if (selection.isEmpty()) ({ openContact(c.threadId) }) else null,
                    )
                }
            }
            if (searching && hits.isNotEmpty()) {
                item(key = "h_msg", contentType = "header") {
                    ListHeader(stringResource(R.string.search_messages, hits.size))
                }
                items(hits, key = { "hit_${it.id}" }, contentType = { "hit" }) { hit ->
                    SearchHitRow(hit, query, settings) { openThreadAt(hit.threadId, hit.id) }
                }
            }
            if (pagingItems.itemCount == 0 && !sync.running && (!searching || hits.isEmpty())) {
                item(key = "empty", contentType = "empty") { EmptyState(searching || filter != null) }
            }
            if (filter == null && spamCount > 0 && !searching) {
                item(key = "spam", contentType = "card") {
                    InfoCard(
                        icon = Icons.Outlined.Shield,
                        text = stringResource(R.string.spam_banner, spamCount),
                        action = stringResource(R.string.show),
                        onAction = { vm.filter.value = Category.SPAM },
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxTopBar(openArchive: () -> Unit, openBin: () -> Unit, openSettings: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(
                stringResource(R.string.inbox_title),
                style = MaterialTheme.typography.headlineMedium,
            )
        },
        actions = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.archive)) },
                        leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                        onClick = { menu = false; openArchive() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recycle_bin)) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = { menu = false; openBin() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings)) },
                        leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                        onClick = { menu = false; openSettings() },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun SelectionTopBar(
    count: Int,
    allPinned: Boolean,
    allMuted: Boolean,
    onClose: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onArchive: () -> Unit,
    onRead: () -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) }
        },
        title = { Text(count.toString()) },
        actions = {
            IconButton(onClick = onPin) {
                Icon(Icons.Outlined.PushPin, stringResource(if (allPinned) R.string.unpin else R.string.pin))
            }
            IconButton(onClick = onArchive) { Icon(Icons.Outlined.Archive, stringResource(R.string.archive)) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (allPinned) R.string.unpin else R.string.pin)) },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                        onClick = { menu = false; onPin() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mark_read)) },
                        leadingIcon = { Icon(Icons.Outlined.DoneAll, null) },
                        onClick = { menu = false; onRead() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (allMuted) R.string.unmute else R.string.mute)) },
                        leadingIcon = {
                            Icon(if (allMuted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff, null)
                        },
                        onClick = { menu = false; onMute() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.block)) },
                        leadingIcon = { Icon(Icons.Outlined.Block, null) },
                        onClick = { menu = false; onBlock() },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(scheme.surfaceContainer)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = scheme.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(stringResource(R.string.search_hint), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, textDirection = TextDirection.Content),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Outlined.Close, contentDescription = stringResource(R.string.clear),
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onChange("") },
            )
        }
    }
}

@Composable
private fun ListHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun FilterChips(selected: Category?, onSelect: (Category?) -> Unit) {
    val options = listOf<Category?>(null, Category.OTP, Category.BANK, Category.OFFERS, Category.PERSONAL, Category.SPAM)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { cat ->
            val isSelected = cat == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(cat) },
                label = { Text(cat?.label() ?: stringResource(R.string.all)) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun OtpCard(message: MessageEntity, onCopied: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var copied by remember(message.id) { mutableStateOf(false) }
    val buttonColor by animateColorAsState(if (copied) scheme.tertiary else scheme.primary, label = "copy")
    val sender = rememberContactName(message.address)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(scheme.primaryContainer)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.VerifiedUser, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.new_code_from, sender),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Outlined.Close, stringResource(R.string.dismiss), tint = scheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onDismiss),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message.otp.orEmpty().chunked(3).joinToString(" "),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                color = scheme.onPrimaryContainer,
                style = MaterialTheme.typography.headlineLarge.copy(textDirection = TextDirection.Ltr),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    NotificationActionReceiver.copyToClipboard(context, message.otp.orEmpty())
                    copied = true
                    onCopied()
                },
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                modifier = Modifier.height(44.dp),
            ) {
                Icon(if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(if (copied) R.string.copied else R.string.copy))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.Schedule, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(15.dp))
            Text(
                stringResource(R.string.code_card_hint),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onPrimaryContainer,
            )
        }
    }
}

/** Live card for the last bulk send: progress, pause / resume, cancel, resend failed. */
@Composable
private fun BulkCard(
    ui: BulkUi,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onResend: () -> Unit,
    onClose: () -> Unit,
    onDetails: () -> Unit,
    onLimit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val c = ui.counts
    val finished = c.finished && !ui.preparing
    val title = when {
        ui.preparing || c.total == 0 -> stringResource(R.string.bulk_preparing)
        finished -> stringResource(R.string.bulk_done_title)
        ui.paused -> stringResource(R.string.bulk_paused_title, c.done, c.total)
        else -> stringResource(R.string.bulk_sending_title, c.done, c.total)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(scheme.surfaceContainer)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(scheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (finished) {
                Icon(
                    Icons.Outlined.Close, stringResource(R.string.dismiss), tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose),
                )
            }
        }
        if (ui.preparing || c.total == 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
        } else {
            LinearProgressIndicator(
                progress = { if (c.total == 0) 0f else c.done.toFloat() / c.total },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                color = if (c.failed > 0 && finished) scheme.error else scheme.primary,
            )
            Text(
                stringResource(R.string.bulk_counts, c.sent, c.failed, c.pending) +
                    (if (c.delivered > 0) " · " + stringResource(R.string.bulk_delivered, c.delivered) else "") +
                    (if (c.retrying > 0) " · " + stringResource(R.string.bulk_retrying, c.retrying) else ""),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            if (!finished && (ui.stalled || ui.waitingLimit)) {
                LimitHint(stalled = ui.stalled, onLimit = onLimit)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                finished && c.failed > 0 -> FilledTonalButton(onClick = onResend) {
                    Text(stringResource(R.string.bulk_resend_failed))
                }
                !finished && !ui.preparing && c.total > 0 -> {
                    FilledTonalButton(onClick = if (ui.paused) onResume else onPause) {
                        Text(stringResource(if (ui.paused) R.string.bulk_resume else R.string.bulk_pause))
                    }
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.bulk_cancel), color = scheme.error)
                    }
                }
            }
            if (c.total > 0) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDetails) { Text(stringResource(R.string.bulk_details)) }
            }
        }
    }
}

/** Why a bulk send is slow right now, with a shortcut to raise Android's limit. */
@Composable
private fun LimitHint(stalled: Boolean, onLimit: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.tertiaryContainer)
            .clickable(onClick = onLimit)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Speed, null, tint = scheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
        Text(
            stringResource(if (stalled) R.string.bulk_stalled else R.string.bulk_waiting_limit),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(stringResource(R.string.send_limit_short), style = MaterialTheme.typography.labelLarge, color = scheme.onTertiaryContainer)
    }
}

/** Live card for messages another app sent (a gateway, a bot…): what went out, what failed. */
@Composable
private fun ExternalCard(
    ui: ExternalUi,
    onDetails: () -> Unit,
    onResend: () -> Unit,
    onClose: () -> Unit,
    onLimit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val c = ui.counts
    val sending = c.pending > 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(scheme.surfaceContainer)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(scheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Forum, null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.external_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(if (sending) R.string.external_sending else R.string.external_done, c.done, c.total),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (!sending) {
                Icon(
                    Icons.Outlined.Close, stringResource(R.string.dismiss), tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose),
                )
            }
        }
        LinearProgressIndicator(
            progress = { if (c.total == 0) 0f else c.done.toFloat() / c.total },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
            color = if (c.failed > 0 && !sending) scheme.error else scheme.secondary,
        )
        Text(
            stringResource(R.string.bulk_counts, c.sent, c.failed, c.pending) +
                (if (c.delivered > 0) " · " + stringResource(R.string.bulk_delivered, c.delivered) else ""),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        if (c.failed > 0 && c.errorHintLimit()) LimitHint(stalled = false, onLimit = onLimit)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (c.failed > 0 && !sending) {
                FilledTonalButton(onClick = onResend) { Text(stringResource(R.string.external_resend)) }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDetails) { Text(stringResource(R.string.bulk_details)) }
        }
    }
}

/** Many failures from another app usually mean Android's limit stopped it. */
private fun BatchCounts.errorHintLimit(): Boolean = failed >= 10

/** One message found by search: who, the matching words highlighted, and when. */
@Composable
private fun SearchHitRow(hit: SearchHit, query: String, settings: AppSettings, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val yesterday = stringResource(R.string.yesterday)
    val highlight = scheme.primary
    val snippet = remember(hit.body, query, highlight) { highlightSnippet(hit.body, query, highlight) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(hit.displayName, hit.addresses, hit.threadId, hit.category, size = 44.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    TimeFormat.short(hit.date, settings, yesterday),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Text(
                snippet,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A short piece of [body] around the first match, with every searched word in bold color. */
private fun highlightSnippet(body: String, query: String, color: Color): AnnotatedString {
    val terms = TextNorm.highlightTerms(query).filter { it.isNotBlank() }
    val folded = TextNorm.fold1to1(body)
    val first = terms.mapNotNull { t -> folded.indexOf(t).takeIf { it >= 0 } }.minOrNull() ?: 0
    val start = (first - 40).coerceAtLeast(0)
    val text = body.substring(start).replace('\n', ' ')
    val foldedPart = folded.substring(start).replace('\n', ' ')
    return buildAnnotatedString {
        if (start > 0) append("…")
        val offset = length
        append(text)
        terms.forEach { t ->
            var i = foldedPart.indexOf(t)
            while (i >= 0) {
                addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), offset + i, offset + i + t.length)
                i = foldedPart.indexOf(t, i + t.length)
            }
        }
    }
}

@Composable
fun InfoCard(icon: ImageVector, text: String, action: String, onAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceContainer)
            .clickable(onClick = onAction)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(action, style = MaterialTheme.typography.labelLarge, color = scheme.primary)
    }
}

@Composable
private fun EmptyState(filtered: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Outlined.Sms, null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(56.dp),
        )
        Text(
            stringResource(if (filtered) R.string.empty_filtered else R.string.empty_inbox),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Swipe actions configured in settings. In RTL "start" is the right edge.
 *
 * The state is a plain `remember` (not saved): when a row comes back after "Undo", it always
 * starts closed — no stuck coloured strip. Actions that keep the row (pin, mute, read) spring back.
 */
@Composable
fun SwipeableRow(
    conversation: ConversationEntity,
    settings: AppSettings,
    enabled: Boolean,
    onSwipe: (SwipeAction, ConversationEntity) -> Unit,
    content: @Composable () -> Unit,
) {
    val startAction = settings.swipeStart
    val endAction = settings.swipeEnd
    val density = LocalDensity.current
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentConversation by rememberUpdatedState(conversation)
    val state = remember(conversation.threadId, startAction, endAction, density) {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            density = density,
            confirmValueChange = { value ->
                val action = when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> startAction
                    SwipeToDismissBoxValue.EndToStart -> endAction
                    SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
                }
                if (action != SwipeAction.NONE) currentOnSwipe(action, currentConversation)
                // Only actions that remove the row from the list stay dismissed.
                action == SwipeAction.ARCHIVE || action == SwipeAction.DELETE
            },
            positionalThreshold = { it * 0.32f },
        )
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = enabled && startAction != SwipeAction.NONE,
        enableDismissFromEndToStart = enabled && endAction != SwipeAction.NONE,
        backgroundContent = {
            val action = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> startAction
                SwipeToDismissBoxValue.EndToStart -> endAction
                SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
            }
            SwipeBackground(action, alignStart = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd)
        },
    ) { content() }
}

@Composable
private fun SwipeBackground(action: SwipeAction, alignStart: Boolean) {
    val scheme = MaterialTheme.colorScheme
    if (action == SwipeAction.NONE) return
    val (bg, fg) = when (action) {
        SwipeAction.DELETE -> scheme.errorContainer to scheme.onErrorContainer
        SwipeAction.ARCHIVE -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    val icon = when (action) {
        SwipeAction.DELETE -> Icons.Outlined.Delete
        SwipeAction.ARCHIVE -> Icons.Outlined.Archive
        SwipeAction.READ -> Icons.Outlined.MarkEmailRead
        SwipeAction.PIN -> Icons.Outlined.PushPin
        SwipeAction.MUTE -> Icons.Outlined.NotificationsOff
        SwipeAction.NONE -> Icons.Outlined.Close
    }
    val label = when (action) {
        SwipeAction.DELETE -> stringResource(R.string.delete)
        SwipeAction.ARCHIVE -> stringResource(R.string.archive_action)
        SwipeAction.READ -> stringResource(R.string.mark_read)
        SwipeAction.PIN -> stringResource(R.string.pin)
        SwipeAction.MUTE -> stringResource(R.string.mute)
        SwipeAction.NONE -> ""
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 24.dp),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = fg)
            Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
        }
    }
}
