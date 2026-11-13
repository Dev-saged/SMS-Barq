package dev.saged.barq.ui.thread

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import dev.saged.barq.ui.components.sharedAvatar
import dev.saged.barq.ui.icons.BIcons
import dev.saged.barq.ui.theme.islandColor
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.saged.barq.notifications.ActiveThread
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import dev.saged.barq.R
import dev.saged.barq.data.db.Box as MsgBox
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.domain.ReactionText
import dev.saged.barq.notifications.NotificationActionReceiver
import dev.saged.barq.ui.components.Avatar
import dev.saged.barq.ui.components.TimeFormat
import dev.saged.barq.ui.inbox.ConfirmDialog

@Composable
fun ThreadScreen(
    onBack: () -> Unit,
    forward: (String) -> Unit,
    openContact: (Long) -> Unit,
    vm: ThreadViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val subId by vm.subId.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val focusIndex by vm.focusIndex.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val searchHits by vm.searchHits.collectAsStateWithLifecycle()
    val searchIndex by vm.searchIndex.collectAsStateWithLifecycle()
    val searchTarget by vm.searchTarget.collectAsStateWithLifecycle()
    val pagingItems = vm.messages.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    var highlightId by remember { mutableStateOf(-1L) }
    var focusDone by remember { mutableStateOf(false) }

    var menu by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var showCategory by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var messageMenu by remember { mutableStateOf<MessageEntity?>(null) }

    val conv = conversation
    val addresses = conv?.addresses.orEmpty()
    val canReply = PhoneUtils.splitAddresses(addresses).all { PhoneUtils.isPhoneNumber(it) }
    val today = stringResource(R.string.today)
    val yesterday = stringResource(R.string.yesterday)

    // While this conversation is on screen, new messages here are read at once (no notification).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ActiveThread.set(vm.threadId)
        vm.refreshSims()
        vm.markRead()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { ActiveThread.clear(vm.threadId) }
    DisposableEffect(Unit) { onDispose { ActiveThread.clear(vm.threadId) } }

    // New messages arrive at index 0 — follow them when already at the bottom. Only a really new
    // message counts (loading older pages while scrolling up changes nothing here).
    val newestId = if (pagingItems.itemCount > 0) pagingItems.peek(0)?.id else null
    LaunchedEffect(newestId) {
        if (newestId == null) return@LaunchedEffect
        if (focusIndex < 0 && listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
        vm.markRead()
    }

    // Opened from a search result: jump to that message and flash it.
    LaunchedEffect(focusIndex, pagingItems.itemCount) {
        if (focusDone || focusIndex < 0 || pagingItems.itemCount <= focusIndex) return@LaunchedEffect
        focusDone = true
        listState.scrollToItem(focusIndex)
        highlightId = vm.focusId
        delay(2_200)
        highlightId = -1L
    }

    // Search in this conversation: jump to the shown result and keep it highlighted.
    BackHandler(enabled = searchQuery != null) { vm.closeSearch() }
    val currentHitId = if (searchQuery != null) searchHits.getOrNull(searchIndex)?.id ?: -1L else -1L
    LaunchedEffect(searchTarget, pagingItems.itemCount) {
        if (searchTarget >= 0 && searchTarget < pagingItems.itemCount) listState.scrollToItem(searchTarget)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (searchQuery != null) {
                ThreadSearchBar(
                    query = searchQuery.orEmpty(),
                    position = if (searchHits.isEmpty()) 0 else searchIndex + 1,
                    total = searchHits.size,
                    onQuery = vm::onSearch,
                    onOlder = vm::olderHit,
                    onNewer = vm::newerHit,
                    onClose = vm::closeSearch,
                )
            } else TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
                title = {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = conv != null) { conv?.let { openContact(it.threadId) } }
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (conv != null) {
                            Avatar(
                                conv.displayName, conv.addresses, conv.threadId, conv.category, size = 40.dp,
                                modifier = Modifier.sharedAvatar(conv.threadId),
                            )
                        }
                        Column {
                            Text(
                                conv?.displayName.orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (conv != null && conv.displayName != conv.addresses) {
                                Text(
                                    conv.addresses,
                                    style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (canReply && PhoneUtils.splitAddresses(addresses).size == 1) {
                        IconButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(addresses))))
                            }
                        }) { Icon(Icons.Outlined.Call, stringResource(R.string.call), tint = MaterialTheme.colorScheme.primary) }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.search_in_conversation)) },
                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                onClick = { menu = false; vm.openSearch() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(if (conv?.pinned == true) R.string.unpin else R.string.pin)) },
                                leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                                onClick = { menu = false; vm.togglePin() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(if (conv?.muted == true) R.string.unmute else R.string.mute)) },
                                leadingIcon = {
                                    Icon(if (conv?.muted == true) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff, null)
                                },
                                onClick = { menu = false; vm.toggleMute() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_notifications)) },
                                leadingIcon = { Icon(Icons.Outlined.NotificationsActive, null) },
                                onClick = { menu = false; vm.openNotificationSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.set_category)) },
                                leadingIcon = { Icon(Icons.Outlined.Category, null) },
                                onClick = { menu = false; showCategory = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.archive)) },
                                leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                                onClick = { menu = false; vm.archive(onBack) },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.block)) },
                                leadingIcon = { Icon(Icons.Outlined.Block, null) },
                                onClick = { menu = false; confirmBlock = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_conversation)) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (canReply) {
                Composer(
                    text = text,
                    count = count,
                    showCounter = settings.showCounter,
                    templates = templates.map { it.text },
                    suggestions = suggestions,
                    onSuggestion = vm::insertTemplate,
                    sims = sims,
                    subId = subId,
                    onText = vm::onTextChange,
                    onTemplate = vm::insertTemplate,
                    onPickSim = vm::pickSim,
                    onSchedule = { showSchedule = true },
                    onSend = { vm.send() },
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.cannot_reply), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            items(
                count = pagingItems.itemCount,
                key = pagingItems.itemKey { it.id },
                contentType = pagingItems.itemContentType { "message" },
            ) { index ->
                val m = pagingItems[index] ?: return@items
                val older = if (index + 1 < pagingItems.itemCount) pagingItems.peek(index + 1) else null
                Column {
                    if (older == null || !TimeFormat.isSameDay(older.date, m.date)) {
                        DayHeader(TimeFormat.dayHeader(m.date, settings, today, yesterday))
                    }
                    Box {
                        MessageBubble(
                            message = m,
                            settings = settings,
                            highlighted = m.id == highlightId || m.id == currentHitId,
                            onLongPress = { messageMenu = m },
                            onResend = { vm.resend(m.id) },
                            onCancelScheduled = { vm.cancelScheduled(m.id) },
                        )
                        MessageMenu(
                            message = messageMenu?.takeIf { it.id == m.id },
                            onDismiss = { messageMenu = null },
                            onCopy = {
                                val cm = context.getSystemService(ClipboardManager::class.java)
                                cm.setPrimaryClip(ClipData.newPlainText("sms", m.body))
                            },
                            onCopyCode = { code -> NotificationActionReceiver.copyToClipboard(context, code) },
                            onForward = { forward(m.body) },
                            onResend = { vm.resend(m.id) },
                            onDelete = { vm.deleteMessage(m.id) },
                            onReact = { emoji -> vm.react(m.id, emoji) },
                            onStar = { vm.setStarred(m.id, !m.starred) },
                        )
                    }
                }
            }
        }
    }

    if (showSchedule) {
        ScheduleDialog(
            onDismiss = { showSchedule = false },
            onPick = { at -> showSchedule = false; vm.send(at) },
        )
    }
    if (showCategory && conv != null) {
        CategoryDialog(
            current = conv.category,
            onDismiss = { showCategory = false },
            onPick = { showCategory = false; vm.setCategory(it) },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            text = stringResource(R.string.confirm_delete_conversation),
            onConfirm = { confirmDelete = false; vm.delete(onBack) },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmBlock) {
        ConfirmDialog(
            text = stringResource(R.string.confirm_block),
            onConfirm = { confirmBlock = false; vm.block(onBack) },
            onDismiss = { confirmBlock = false },
        )
    }
}

@Composable
private fun MessageMenu(
    message: MessageEntity?,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onCopyCode: (String) -> Unit,
    onForward: () -> Unit,
    onResend: () -> Unit,
    onDelete: () -> Unit,
    onReact: (String) -> Unit,
    onStar: () -> Unit,
) {
    DropdownMenu(expanded = message != null, onDismissRequest = onDismiss) {
        if (message == null) return@DropdownMenu
        // Reactions first: one tap, like in chat apps. The same one again removes it.
        if (message.box != MsgBox.QUEUED || message.scheduledAt == 0L) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ReactionText.choices.forEach { emoji ->
                    val chosen = message.reaction == emoji
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
                            .clickable { onDismiss(); onReact(emoji) },
                        contentAlignment = Alignment.Center,
                    ) { dev.saged.barq.ui.components.Emoji(emoji, 30.dp) }
                }
            }
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.copy_text)) },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
            onClick = { onDismiss(); onCopy() },
        )
        message.otp?.let { code ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy_code_value, code)) },
                leadingIcon = { Icon(Icons.Outlined.VerifiedUser, null) },
                onClick = { onDismiss(); onCopyCode(code) },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(if (message.starred) R.string.unstar else R.string.star)) },
            leadingIcon = { Icon(if (message.starred) Icons.Outlined.StarOutline else Icons.Outlined.Star, null) },
            onClick = { onDismiss(); onStar() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.forward)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null) },
            onClick = { onDismiss(); onForward() },
        )
        if (message.box == MsgBox.FAILED) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.resend)) },
                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                onClick = { onDismiss(); onResend() },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDelete() },
        )
    }
}

/** The top bar while searching inside the conversation: field, "3 / 12", older / newer, close. */
@Composable
private fun ThreadSearchBar(
    query: String,
    position: Int,
    total: Int,
    onQuery: (String) -> Unit,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.close_search)) }
        },
        title = {
            Box(Modifier.fillMaxWidth()) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.search_in_conversation_hint), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, textDirection = TextDirection.Content),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        actions = {
            if (query.isNotBlank()) {
                Text(
                    if (total == 0) stringResource(R.string.search_no_results) else "$position / $total",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            IconButton(onClick = onOlder, enabled = total > 0) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.search_older)) }
            IconButton(onClick = onNewer, enabled = total > 0) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.search_newer)) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surfaceContainer),
    )
}

@Composable
fun Composer(
    text: String,
    count: SmsCount,
    showCounter: Boolean,
    templates: List<String>,
    sims: List<dev.saged.barq.data.sim.SimInfo>,
    subId: Int,
    onText: (String) -> Unit,
    onTemplate: (String) -> Unit,
    suggestions: List<String> = emptyList(),
    onSuggestion: (String) -> Unit = {},
    onPickSim: (Int) -> Unit,
    onSchedule: () -> Unit,
    onSend: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.background)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        dev.saged.barq.ui.components.SimSwitch(sims, subId, onPickSim)
        if (text.isBlank() && suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEachIndexed { i, r ->
                    AssistChip(
                        onClick = { onSuggestion(r) },
                        label = { Text(r, maxLines = 1) },
                        leadingIcon = if (i == 0) {
                            { Icon(BIcons.Sparkle, null, Modifier.size(16.dp), tint = scheme.primary) }
                        } else null,
                        shape = RoundedCornerShape(18.dp),
                        colors = AssistChipDefaults.assistChipColors(containerColor = scheme.primaryContainer, labelColor = scheme.onPrimaryContainer),
                        border = null,
                    )
                }
            }
        }
        if (templates.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                templates.forEachIndexed { i, t ->
                    AssistChip(
                        onClick = { onTemplate(t) },
                        label = { Text(t, maxLines = 1) },
                        leadingIcon = if (i == 0) {
                            { Icon(Icons.Outlined.Bolt, null, Modifier.size(16.dp), tint = scheme.primary) }
                        } else null,
                        shape = RoundedCornerShape(10.dp),
                        colors = AssistChipDefaults.assistChipColors(containerColor = islandColor()),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(islandColor())
                    .padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (text.isEmpty()) {
                        Text(stringResource(R.string.compose_hint), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onText,
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, textDirection = TextDirection.Content),
                        cursorBrush = SolidColor(scheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (showCounter && count.parts > 0) {
                    Text(
                        "${count.parts} · ${count.remaining}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (count.parts > 1) scheme.primary else scheme.onSurfaceVariant,
                        fontWeight = if (count.parts > 1) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                IconButton(onClick = onSchedule, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Schedule, stringResource(R.string.schedule_send), tint = scheme.primary)
                }
            }
            FilledIconButton(
                onClick = onSend,
                enabled = text.isNotBlank(),
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.primary),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.send))
            }
        }
    }
}
