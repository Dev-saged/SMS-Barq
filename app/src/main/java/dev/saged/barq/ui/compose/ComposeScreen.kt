package dev.saged.barq.ui.compose

import android.telephony.SmsMessage
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.R
import dev.saged.barq.data.contacts.ContactEntry
import dev.saged.barq.data.contacts.ContactsRepository
import dev.saged.barq.data.contacts.PhoneGroup
import dev.saged.barq.data.contacts.PhoneGroups
import dev.saged.barq.data.db.GroupDao
import dev.saged.barq.data.db.SendGroupWithCount
import dev.saged.barq.data.db.TemplateDao
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.sim.SimInfo
import dev.saged.barq.data.sim.SimRepository
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.sms.SendEngine
import dev.saged.barq.ui.components.ContactList
import dev.saged.barq.ui.thread.Composer
import dev.saged.barq.ui.thread.ScheduleDialog
import dev.saged.barq.ui.thread.SmsCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class Recipient(val address: String, val name: String?)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ComposeViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val contacts: ContactsRepository,
    private val engine: SendEngine,
    private val groupDao: GroupDao,
    private val settingsRepository: SettingsRepository,
    private val simRepository: SimRepository,
    private val phoneGroups: PhoneGroups,
    templateDao: TemplateDao,
) : ViewModel() {

    val recipients = MutableStateFlow(
        savedState.get<String>("to").orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            .map { Recipient(it, contacts.lookupCached(it)?.name) }
    )
    val query = MutableStateFlow("")
    val text = MutableStateFlow(savedState.get<String>("body").orEmpty())
    val count = MutableStateFlow(SmsCount(0, 0))
    val sims = MutableStateFlow<List<SimInfo>>(emptyList())
    val subId = MutableStateFlow(-1)

    val templates = templateDao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val groups = groupDao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<SendGroupWithCount>())
    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Groups made in the phone's Contacts app. */
    val deviceGroups = MutableStateFlow<List<PhoneGroup>>(emptyList())

    /** A phone group is being read (big groups take a moment). */
    val addingGroup = MutableStateFlow(false)

    /** Every contact on the phone, sorted, with letter sections. */
    val allContacts = contacts.entries

    /** Instant local search results (empty while the box is empty). */
    val results = query.debounce(120).mapLatest { q -> if (q.isBlank()) emptyList() else contacts.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        onText(text.value)
        viewModelScope.launch(Dispatchers.IO) {
            contacts.warmUp()
            deviceGroups.value = runCatching { phoneGroups.groups() }.getOrDefault(emptyList())
        }
        refreshSims()
    }

    /** Adds one number of every person in a phone group; each gets a separate SMS. */
    fun addPhoneGroup(group: PhoneGroup) {
        if (addingGroup.value) return
        addingGroup.value = true
        viewModelScope.launch {
            try {
                val numbers = phoneGroups.numbers(group)
                val current = recipients.value.toMutableList()
                val keys = current.mapTo(HashSet()) { PhoneUtils.matchKey(it.address) }
                numbers.forEach { (number, name) ->
                    if (keys.add(PhoneUtils.matchKey(number))) current += Recipient(number, name)
                }
                recipients.value = current
            } finally {
                addingGroup.value = false
            }
        }
    }

    fun clearRecipients() {
        recipients.value = emptyList()
    }

    /** Re-read on every resume, so a SIM appears right after the phone permission is granted. */
    fun refreshSims() {
        viewModelScope.launch {
            val list = simRepository.activeSims()
            sims.value = list
            if (list.none { it.subId == subId.value }) {
                val def = settingsRepository.current().defaultSubId
                subId.value = if (list.any { it.subId == def }) def else list.firstOrNull()?.subId ?: -1
            }
        }
    }

    fun onText(value: String) {
        text.value = value
        count.value = if (value.isEmpty()) SmsCount(0, 0) else runCatching {
            val r = SmsMessage.calculateLength(value, false)
            SmsCount(r[0], r[2])
        }.getOrDefault(SmsCount(1, 0))
    }

    fun add(entry: ContactEntry) = add(entry.number, entry.name)

    fun add(address: String, name: String? = null) {
        val a = address.trim()
        if (a.isEmpty()) return
        if (recipients.value.none { PhoneUtils.same(it.address, a) }) {
            recipients.value = recipients.value + Recipient(a, name ?: contacts.lookupCached(a)?.name)
        }
        query.value = ""
    }

    /** Adds every member of a saved group. */
    fun addGroup(groupId: Long) {
        viewModelScope.launch {
            val members = groupDao.members(groupId)
            val current = recipients.value.toMutableList()
            val keys = current.mapTo(HashSet()) { PhoneUtils.matchKey(it.address) }
            members.forEach { m ->
                if (keys.add(PhoneUtils.matchKey(m.address))) {
                    current += Recipient(m.address, m.name ?: contacts.lookupCached(m.address)?.name)
                }
            }
            recipients.value = current
        }
    }

    /** Pasted lists ("0599…, 0598…" or one per line) become many recipients at once. */
    fun addTyped() {
        val parts = query.value.split(',', ';', '\n', '،').map { it.trim() }.filter { PhoneUtils.isPhoneNumber(it) }
        parts.forEach { add(it) }
    }

    fun remove(r: Recipient) {
        recipients.value = recipients.value - r
    }

    fun cycleSim() {
        val list = sims.value
        if (list.size < 2) return
        val i = list.indexOfFirst { it.subId == subId.value }
        subId.value = list[(i + 1).mod(list.size)].subId
    }

    /** True when this send needs the one-time explanation about bulk sending first. */
    fun needsBulkIntro(): Boolean {
        addTyped()
        return recipients.value.size > 1 && settings.value?.bulkIntroShown != true
    }

    fun bulkIntroSeen() {
        viewModelScope.launch { settingsRepository.setBulkIntroShown(true) }
    }

    fun send(scheduledAt: Long = 0, done: (SendEngine.Outcome) -> Unit) {
        addTyped()
        val to = recipients.value.map { it.address }
        val body = text.value
        if (to.isEmpty() || body.isBlank()) return
        viewModelScope.launch {
            done(engine.sendNew(to, body, subId.value.takeIf { it >= 0 }, scheduledAt))
        }
    }
}

@Composable
fun ComposeScreen(
    onBack: () -> Unit,
    onSent: (Long) -> Unit,
    onSentMany: () -> Unit,
    vm: ComposeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val recipients by vm.recipients.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val all by vm.allContacts.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val deviceGroups by vm.deviceGroups.collectAsStateWithLifecycle()
    val addingGroup by vm.addingGroup.collectAsStateWithLifecycle()
    var showAllRecipients by remember { mutableStateOf(false) }
    val text by vm.text.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val subId by vm.subId.collectAsStateWithLifecycle()
    var showSchedule by remember { mutableStateOf(false) }
    var bulkIntro by remember { mutableStateOf<Long?>(null) }
    val focus = remember { FocusRequester() }
    val sendingMany = stringResource(R.string.sending_many)
    val scheduledMany = stringResource(R.string.scheduled_many)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshSims() }
    LaunchedEffect(Unit) { if (recipients.isEmpty()) runCatching { focus.requestFocus() } }

    val afterSend: (SendEngine.Outcome) -> Unit = { outcome ->
        when (outcome) {
            is SendEngine.Outcome.Single -> onSent(outcome.threadId)
            is SendEngine.Outcome.Bulk -> {
                val msg = if (outcome.batchId != 0L) sendingMany else scheduledMany
                Toast.makeText(context, String.format(msg, outcome.count), Toast.LENGTH_SHORT).show()
                onSentMany()
            }
            SendEngine.Outcome.Empty -> Unit
        }
    }
    fun trySend(at: Long) {
        if (vm.needsBulkIntro()) bulkIntro = at else vm.send(at, afterSend)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
                title = { Text(stringResource(R.string.new_message)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Composer(
                text = text,
                count = count,
                showCounter = true,
                templates = templates.map { it.text },
                simLabel = sims.takeIf { it.size > 1 }?.firstOrNull { it.subId == subId }?.let { "SIM ${it.slot + 1}" },
                onText = vm::onText,
                onTemplate = { t -> vm.onText(if (text.isBlank()) t else "$text $t") },
                onSim = vm::cycleSim,
                onSchedule = { showSchedule = true },
                onSend = { trySend(0) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val scheme = MaterialTheme.colorScheme
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(scheme.surfaceContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (recipients.isNotEmpty()) {
                    // Hundreds of people: show a few names and a summary instead of a wall of chips.
                    val shown = if (showAllRecipients || recipients.size <= MAX_CHIPS) recipients else recipients.take(MAX_CHIPS)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        shown.forEach { r ->
                            InputChip(
                                selected = false,
                                onClick = { vm.remove(r) },
                                label = { Text(r.name ?: r.address) },
                                trailingIcon = { Icon(Icons.Outlined.Close, null, Modifier.padding(2.dp)) },
                                shape = RoundedCornerShape(10.dp),
                                colors = InputChipDefaults.inputChipColors(
                                    containerColor = scheme.secondaryContainer,
                                    labelColor = scheme.onSecondaryContainer,
                                    trailingIconColor = scheme.onSecondaryContainer,
                                ),
                            )
                        }
                        if (recipients.size > MAX_CHIPS) {
                            AssistChip(
                                onClick = { showAllRecipients = !showAllRecipients },
                                label = {
                                    Text(
                                        if (showAllRecipients) stringResource(R.string.recipients_less)
                                        else stringResource(R.string.recipients_more, recipients.size - MAX_CHIPS)
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                            )
                            AssistChip(
                                onClick = { vm.clearRecipients(); showAllRecipients = false },
                                label = { Text(stringResource(R.string.recipients_clear)) },
                                leadingIcon = { Icon(Icons.Outlined.Close, null, Modifier.size(16.dp)) },
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.to_label), color = scheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
                    Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        if (query.isEmpty()) {
                            Text(stringResource(R.string.recipient_hint), color = scheme.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = {
                                if (it.endsWith(",") || it.endsWith("\n")) {
                                    vm.query.value = it.dropLast(1)
                                    vm.addTyped()
                                } else {
                                    vm.query.value = it
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (recipients.isEmpty()) KeyboardType.Text else KeyboardType.Phone,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { vm.addTyped() }),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, textDirection = TextDirection.Content),
                            cursorBrush = SolidColor(scheme.primary),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    if (PhoneUtils.isPhoneNumber(query)) {
                        IconButton(onClick = vm::addTyped) { Icon(Icons.Outlined.PersonAdd, stringResource(R.string.add), tint = scheme.primary) }
                    }
                }
            }
            if (recipients.size > 1) {
                Text(
                    stringResource(R.string.bulk_hint, recipients.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
            }

            val searching = query.isNotBlank()
            when {
                searching -> ContactList(
                    entries = results,
                    sectioned = false,
                    onClick = vm::add,
                )
                // Someone is chosen: keep the screen calm, the list comes back as soon as you type.
                recipients.isNotEmpty() -> {
                    if (groups.isNotEmpty() || deviceGroups.isNotEmpty()) {
                        GroupChips(groups, deviceGroups, addingGroup, vm::addGroup, vm::addPhoneGroup)
                    }
                    Text(
                        stringResource(R.string.add_more_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                else -> ContactList(
                    entries = all,
                    sectioned = true,
                    onClick = vm::add,
                    headerCount = if (groups.isNotEmpty() || deviceGroups.isNotEmpty()) 1 else 0,
                    header = {
                        if (groups.isNotEmpty() || deviceGroups.isNotEmpty()) {
                            item(key = "groups") {
                                GroupChips(groups, deviceGroups, addingGroup, vm::addGroup, vm::addPhoneGroup)
                            }
                        }
                    },
                )
            }
        }
    }

    if (showSchedule) {
        ScheduleDialog(
            onDismiss = { showSchedule = false },
            onPick = { at -> showSchedule = false; trySend(at) },
        )
    }
    bulkIntro?.let { at ->
        AlertDialog(
            onDismissRequest = { bulkIntro = null },
            title = { Text(stringResource(R.string.bulk_intro_title)) },
            text = { Text(stringResource(R.string.bulk_intro_text)) },
            confirmButton = {
                TextButton(onClick = {
                    bulkIntro = null
                    vm.bulkIntroSeen()
                    vm.send(at, afterSend)
                }) { Text(stringResource(R.string.send)) }
            },
            dismissButton = { TextButton(onClick = { bulkIntro = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private const val MAX_CHIPS = 12

/** Barq's saved groups, then the phone's own contact groups. One tap adds everybody. */
@Composable
private fun GroupChips(
    groups: List<SendGroupWithCount>,
    deviceGroups: List<PhoneGroup>,
    adding: Boolean,
    onPick: (Long) -> Unit,
    onPickPhone: (PhoneGroup) -> Unit,
) {
    Column {
        Text(
            stringResource(if (adding) R.string.groups_adding else R.string.groups_send_to),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(groups, key = { "barq_${it.id}" }) { g ->
                AssistChip(
                    onClick = { onPick(g.id) },
                    label = { Text(stringResource(R.string.group_chip, g.name, g.members)) },
                    leadingIcon = { Icon(Icons.Outlined.Groups, null, Modifier.size(18.dp)) },
                    shape = RoundedCornerShape(12.dp),
                    colors = AssistChipDefaults.assistChipColors(leadingIconContentColor = MaterialTheme.colorScheme.primary),
                )
            }
            items(deviceGroups, key = { "phone_${it.title}" }) { g ->
                AssistChip(
                    onClick = { onPickPhone(g) },
                    enabled = !adding,
                    label = { Text(stringResource(R.string.group_chip, g.title, g.members)) },
                    leadingIcon = { Icon(Icons.Outlined.Contacts, null, Modifier.size(18.dp)) },
                    shape = RoundedCornerShape(12.dp),
                    colors = AssistChipDefaults.assistChipColors(leadingIconContentColor = MaterialTheme.colorScheme.tertiary),
                )
            }
        }
    }
}
