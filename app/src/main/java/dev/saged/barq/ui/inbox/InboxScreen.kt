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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.saged.barq.R
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.ConversationEntity
import dev.saged.barq.data.db.MessageEntity
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SwipeAction
import dev.saged.barq.notifications.NotificationActionReceiver
import dev.saged.barq.ui.MainViewModel
import dev.saged.barq.ui.components.ConversationRow
import dev.saged.barq.ui.components.label
import dev.saged.barq.ui.components.rememberDefaultSmsRequest
import kotlinx.coroutines.launch

@Composable
fun InboxScreen(
    mainViewModel: MainViewModel,
    openThread: (Long) -> Unit,
    newMessage: () -> Unit,
    openArchive: () -> Unit,
    openBin: () -> Unit,
    openSettings: () -> Unit,
    openContact: (Long) -> Unit,
    vm: InboxViewModel = hiltViewModel(),
) {
    val pagingItems = vm.conversationsPaged.collectAsLazyPagingItems()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val otp by vm.freshOtp.collectAsStateWithLifecycle()
    val spamCount by vm.spamCount.collectAsStateWithLifecycle()
    val isDefault by mainViewModel.isDefault.collectAsStateWithLifecycle()
    val sync by mainViewModel.syncProgress.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val expandedFab by remember { derivedStateOf { listState.firstVisibleItemIndex < 2 } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val requestDefault = rememberDefaultSmsRequest { mainViewModel.onPermissionsChanged() }
    val archivedMsg = stringResource(R.string.archived_done)
    val deletedMsg = stringResource(R.string.deleted_done)
    val undo = stringResource(R.string.undo)

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
                SelectionTopBar(
                    count = selection.size,
                    onClose = vm::clearSelection,
                    onPin = { vm.pinSelected(true) },
                    onMute = { vm.muteSelected(true) },
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
                item(key = "default") {
                    InfoCard(
                        icon = Icons.Outlined.Sms,
                        text = stringResource(R.string.not_default_banner),
                        action = stringResource(R.string.make_default),
                        onAction = requestDefault,
                    )
                }
            }
            item(key = "search") { SearchField(query) { vm.query.value = it } }
            item(key = "chips") { FilterChips(filter) { vm.filter.value = it } }
            item(key = "otp") {
                AnimatedVisibility(
                    visible = otp != null && filter == null && query.isBlank(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    otp?.let { OtpCard(it) { vm.dismissOtp(it.id) } }
                }
            }
            if (sync.running && sync.imported > 0) {
                item(key = "sync") {
                    Text(
                        stringResource(R.string.importing, sync.imported),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            items(count = pagingItems.itemCount, key = pagingItems.itemKey { it.threadId }) { index ->
                val c = pagingItems[index] ?: return@items
                SwipeableRow(
                    conversation = c,
                    settings = settings,
                    enabled = selection.isEmpty(),
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
            if (pagingItems.itemCount == 0 && !sync.running) {
                item(key = "empty") { EmptyState(query.isNotBlank() || filter != null) }
            }
            if (filter == null && spamCount > 0 && query.isBlank()) {
                item(key = "spam") {
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
            IconButton(onClick = onPin) { Icon(Icons.Outlined.PushPin, stringResource(R.string.pin)) }
            IconButton(onClick = onArchive) { Icon(Icons.Outlined.Archive, stringResource(R.string.archive)) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mark_read)) },
                        leadingIcon = { Icon(Icons.Outlined.DoneAll, null) },
                        onClick = { menu = false; onRead() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mute)) },
                        leadingIcon = { Icon(Icons.Outlined.NotificationsOff, null) },
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
private fun OtpCard(message: MessageEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var copied by remember(message.id) { mutableStateOf(false) }
    val buttonColor by animateColorAsState(if (copied) scheme.tertiary else scheme.primary, label = "copy")
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
                stringResource(R.string.new_code_from, message.address),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onPrimaryContainer,
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
                },
                shape = RoundedCornerShape(22.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = buttonColor),
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

/** Swipe actions configured in settings. In RTL "start" is the right edge. */
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
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val action = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> startAction
                SwipeToDismissBoxValue.EndToStart -> endAction
                SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
            }
            if (action != SwipeAction.NONE) onSwipe(action, conversation)
            // Only actions that remove the row from the list stay dismissed.
            action == SwipeAction.ARCHIVE || action == SwipeAction.DELETE
        },
        positionalThreshold = { it * 0.32f },
    )
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
        SwipeAction.READ -> Icons.Outlined.DoneAll
        SwipeAction.PIN -> Icons.Outlined.PushPin
        SwipeAction.MUTE -> Icons.Outlined.NotificationsOff
        SwipeAction.NONE -> Icons.AutoMirrored.Outlined.ArrowBack
    }
    Box(
        modifier = Modifier.fillMaxSize().background(bg).padding(horizontal = 28.dp),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Icon(icon, contentDescription = null, tint = fg)
    }
}
