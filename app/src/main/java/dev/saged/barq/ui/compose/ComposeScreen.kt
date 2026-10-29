package dev.saged.barq.ui.compose

import android.telephony.SmsMessage
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.R
import dev.saged.barq.data.MessageRepository
import dev.saged.barq.data.contacts.Contact
import dev.saged.barq.data.contacts.ContactsRepository
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.TemplateDao
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.data.sim.SimRepository
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.ui.components.Avatar
import dev.saged.barq.ui.thread.Composer
import dev.saged.barq.ui.thread.ScheduleDialog
import dev.saged.barq.ui.thread.SmsCount
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
    private val repository: MessageRepository,
    templateDao: TemplateDao,
    settingsRepository: SettingsRepository,
    simRepository: SimRepository,
) : ViewModel() {

    val recipients = MutableStateFlow(
        savedState.get<String>("to").orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            .map { Recipient(it, contacts.nameFor(it)) }
    )
    val query = MutableStateFlow("")
    val text = MutableStateFlow(savedState.get<String>("body").orEmpty())
    val count = MutableStateFlow(SmsCount(0, 0))
    val sims = simRepository.activeSims()
    val subId = MutableStateFlow(sims.firstOrNull()?.subId ?: -1)

    val templates = templateDao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val suggestions = query.debounce(150).mapLatest { contacts.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        onText(text.value)
        viewModelScope.launch {
            val def = settingsRepository.current().defaultSubId
            if (def >= 0 && sims.any { it.subId == def }) subId.value = def
        }
    }

    fun onText(value: String) {
        text.value = value
        count.value = if (value.isEmpty()) SmsCount(0, 0) else runCatching {
            val r = SmsMessage.calculateLength(value, false)
            SmsCount(r[0], r[2])
        }.getOrDefault(SmsCount(1, 0))
    }

    fun add(contact: Contact) = add(contact.number, contact.name)

    fun add(address: String, name: String? = null) {
        val a = address.trim()
        if (a.isEmpty()) return
        if (recipients.value.none { PhoneUtils.same(it.address, a) }) {
            recipients.value = recipients.value + Recipient(a, name ?: contacts.nameFor(a))
        }
        query.value = ""
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
        if (sims.size < 2) return
        val i = sims.indexOfFirst { it.subId == subId.value }
        subId.value = sims[(i + 1).mod(sims.size)].subId
    }

    fun send(scheduledAt: Long = 0, done: (List<Long>) -> Unit) {
        addTyped()
        val to = recipients.value.map { it.address }
        val body = text.value
        if (to.isEmpty() || body.isBlank()) return
        viewModelScope.launch {
            done(repository.queueOutgoing(to, body, subId.value, scheduledAt))
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
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val subId by vm.subId.collectAsStateWithLifecycle()
    var showSchedule by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val sendingMany = stringResource(R.string.sending_many)

    LaunchedEffect(Unit) { if (recipients.isEmpty()) runCatching { focus.requestFocus() } }

    val afterSend: (List<Long>) -> Unit = { ids ->
        if (ids.size == 1) onSent(ids.first()) else {
            Toast.makeText(context, String.format(sendingMany, ids.size), Toast.LENGTH_SHORT).show()
            onSentMany()
        }
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
                simLabel = vm.sims.takeIf { it.size > 1 }?.firstOrNull { it.subId == subId }?.let { "SIM ${it.slot + 1}" },
                onText = vm::onText,
                onTemplate = { t -> vm.onText(if (text.isBlank()) t else "$text $t") },
                onSim = vm::cycleSim,
                onSchedule = { showSchedule = true },
                onSend = { vm.send(done = afterSend) },
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        recipients.forEach { r ->
                            InputChip(
                                selected = false,
                                onClick = { vm.remove(r) },
                                label = { Text(r.name ?: r.address) },
                                trailingIcon = { Icon(Icons.Outlined.Close, null, Modifier.padding(2.dp)) },
                                shape = RoundedCornerShape(10.dp),
                                colors = InputChipDefaults.inputChipColors(containerColor = scheme.secondaryContainer),
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
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
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
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                items(suggestions, key = { it.number }) { c ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { vm.add(c) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Avatar(c.name, c.number, c.number.hashCode().toLong(), Category.PERSONAL, size = 42.dp)
                        Column {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                c.number,
                                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSchedule) {
        ScheduleDialog(
            onDismiss = { showSchedule = false },
            onPick = { at -> showSchedule = false; vm.send(at, afterSend) },
        )
    }
}
