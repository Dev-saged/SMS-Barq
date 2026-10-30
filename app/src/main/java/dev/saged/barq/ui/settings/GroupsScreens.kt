package dev.saged.barq.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.R
import dev.saged.barq.data.contacts.ContactEntry
import dev.saged.barq.data.contacts.ContactsRepository
import androidx.room.withTransaction
import dev.saged.barq.data.contacts.PhoneGroup
import dev.saged.barq.data.contacts.PhoneGroups
import dev.saged.barq.data.db.BarqDatabase
import dev.saged.barq.data.db.GroupDao
import dev.saged.barq.data.db.SendGroupEntity
import dev.saged.barq.data.db.SendGroupMemberEntity
import dev.saged.barq.data.db.SendGroupWithCount
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.ui.components.ContactList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------------- view model

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class GroupsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val dao: GroupDao,
    private val contacts: ContactsRepository,
    private val phoneGroups: PhoneGroups,
    private val db: BarqDatabase,
) : ViewModel() {
    val groupId: Long = savedState.get<Long>("groupId") ?: -1L

    val groups = dao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<SendGroupWithCount>())
    val group = (if (groupId > 0) dao.observeGroup(groupId) else flowOf(null))
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val members = (if (groupId > 0) dao.observeMembers(groupId) else flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<SendGroupMemberEntity>())

    // Picker
    val query = MutableStateFlow("")
    val picked = MutableStateFlow<Set<String>>(emptySet())
    private var pickerReady = false
    val entries = combine(contacts.entries, query.debounce(100)) { all, q -> all to q }
        .mapLatest { (all, q) -> if (q.isBlank()) all else contacts.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<ContactEntry>())

    /** Groups made in the phone's Contacts app (for merging). */
    val deviceGroups = MutableStateFlow<List<PhoneGroup>>(emptyList())

    /** Filter for a long member list (name or number). */
    val memberQuery = MutableStateFlow("")
    val visibleMembers = combine(members, memberQuery) { list, q ->
        val t = q.trim()
        if (t.isEmpty()) list
        else {
            val digits = PhoneUtils.normalizeDigits(t).filter { it.isDigit() }
            list.filter { m ->
                m.name?.contains(t, ignoreCase = true) == true ||
                    m.address.contains(t) ||
                    (digits.length >= 3 && m.address.filter { it.isDigit() }.contains(digits.trimStart('0')))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<SendGroupMemberEntity>())

    /** Busy merging / cleaning (big groups take a moment). */
    val working = MutableStateFlow(false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            contacts.warmUp()
            deviceGroups.value = runCatching { phoneGroups.groups() }.getOrDefault(emptyList())
        }
    }

    /**
     * Makes a new Barq group from several groups (Barq's and the phone's): numbers in the local
     * form, each real number once. Reports how many numbers it has and how many repeats were dropped.
     */
    fun merge(name: String, barqIds: Set<Long>, phone: List<PhoneGroup>, done: (Long, Int, Int) -> Unit) {
        if (name.isBlank() || (barqIds.isEmpty() && phone.isEmpty()) || working.value) return
        working.value = true
        viewModelScope.launch {
            try {
                val all = ArrayList<Pair<String, String?>>()
                barqIds.forEach { id -> dao.members(id).mapTo(all) { it.address to it.name } }
                phone.forEach { g -> all += phoneGroups.numbers(g) }
                val (unique, repeated) = clean(all)
                val id = db.withTransaction {
                    val id = dao.insert(SendGroupEntity(name = name.trim()))
                    dao.addMembers(unique.map { (a, n) -> SendGroupMemberEntity(id, a, n) })
                    id
                }
                done(id, unique.size, repeated)
            } finally {
                working.value = false
            }
        }
    }

    /** Writes every number of this group in the local form and removes repeated numbers. */
    fun cleanUp(done: (Int) -> Unit) {
        if (groupId <= 0 || working.value) return
        working.value = true
        viewModelScope.launch {
            try {
                val current = dao.members(groupId)
                val (unique, repeated) = clean(current.map { it.address to it.name })
                db.withTransaction {
                    dao.clearMembers(groupId)
                    dao.addMembers(unique.map { (a, n) -> SendGroupMemberEntity(groupId, a, n) })
                }
                done(repeated)
            } finally {
                working.value = false
            }
        }
    }

    /** Local form + one entry per real number (the first name found is kept). */
    private fun clean(numbers: List<Pair<String, String?>>): Pair<List<Pair<String, String?>>, Int> {
        val byKey = LinkedHashMap<String, Pair<String, String?>>()
        var repeated = 0
        numbers.forEach { (address, name) ->
            if (!PhoneUtils.isPhoneNumber(address)) return@forEach
            val local = PhoneUtils.localPs(address)
            val key = PhoneUtils.matchKey(local)
            val before = byKey[key]
            if (before == null) byKey[key] = local to (name ?: contacts.lookupCached(local)?.name)
            else {
                repeated++
                if (before.second == null && name != null) byKey[key] = before.first to name
            }
        }
        return byKey.values.toList() to repeated
    }

    fun create(name: String, done: (Long) -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch { done(dao.insert(SendGroupEntity(name = name.trim()))) }
    }

    fun rename(name: String) {
        if (name.isBlank() || groupId <= 0) return
        viewModelScope.launch { dao.rename(groupId, name.trim()) }
    }

    fun delete(done: () -> Unit) {
        viewModelScope.launch {
            dao.clearMembers(groupId)
            dao.deleteGroup(groupId)
            done()
        }
    }

    fun removeMember(m: SendGroupMemberEntity) {
        viewModelScope.launch { dao.removeMember(m.groupId, m.address) }
    }

    /** Pasted numbers: one per line or separated by commas. */
    fun addPasted(text: String) {
        val numbers = text.split(',', ';', '\n', '،').map { it.trim() }.filter { PhoneUtils.isPhoneNumber(it) }
            .map { PhoneUtils.localPs(it) }
        if (numbers.isEmpty() || groupId <= 0) return
        viewModelScope.launch {
            val existing = dao.members(groupId).map { PhoneUtils.matchKey(it.address) }.toSet()
            dao.addMembers(
                numbers.distinctBy { PhoneUtils.matchKey(it) }
                    .filter { PhoneUtils.matchKey(it) !in existing }
                    .map { SendGroupMemberEntity(groupId, it, contacts.lookupCached(it)?.name) }
            )
        }
    }

    /** The picker starts with the group's current members already ticked. */
    fun preparePicker(all: List<ContactEntry>) {
        if (pickerReady || all.isEmpty()) return
        pickerReady = true
        viewModelScope.launch {
            val keys = dao.members(groupId).map { PhoneUtils.matchKey(it.address) }.toSet()
            picked.value = all.filter { PhoneUtils.matchKey(it.number) in keys }.map { it.key }.toSet()
        }
    }

    fun toggle(e: ContactEntry) {
        picked.value = picked.value.let { if (e.key in it) it - e.key else it + e.key }
    }

    /** Saves the ticked contacts: new ones are added, unticked contacts are removed. */
    fun savePicked(done: () -> Unit) {
        viewModelScope.launch {
            val all = contacts.entries.value
            val chosen = all.filter { it.key in picked.value }
            val chosenKeys = chosen.map { PhoneUtils.matchKey(it.number) }.toSet()
            val contactKeys = all.map { PhoneUtils.matchKey(it.number) }.toSet()
            val current = dao.members(groupId)
            current.filter { PhoneUtils.matchKey(it.address) in contactKeys && PhoneUtils.matchKey(it.address) !in chosenKeys }
                .forEach { dao.removeMember(groupId, it.address) }
            val currentKeys = current.map { PhoneUtils.matchKey(it.address) }.toSet()
            dao.addMembers(
                chosen.filter { PhoneUtils.matchKey(it.number) !in currentKeys }
                    .distinctBy { PhoneUtils.matchKey(it.number) }
                    .map { SendGroupMemberEntity(groupId, PhoneUtils.localPs(it.number), it.name) }
            )
            done()
        }
    }
}

// ---------------------------------------------------------------------------------- list of groups

@Composable
fun GroupsScreen(onBack: () -> Unit, openGroup: (Long) -> Unit, vm: GroupsViewModel = hiltViewModel()) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val deviceGroups by vm.deviceGroups.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val mergedMsg = stringResource(R.string.group_merged)

    SettingsScaffold(
        title = stringResource(R.string.groups_title),
        onBack = onBack,
        floating = { AddFab(stringResource(R.string.group_new)) { adding = true } },
    ) {
        item {
            Text(
                stringResource(R.string.groups_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            )
            if (groups.size + deviceGroups.size >= 2) {
                FilledTonalButton(onClick = { merging = true }, enabled = !working, modifier = Modifier.padding(bottom = 8.dp)) {
                    Icon(Icons.Outlined.SelectAll, null)
                    Text(
                        stringResource(if (working) R.string.groups_adding else R.string.group_merge),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        if (groups.isEmpty()) item { EmptyHint(stringResource(R.string.nothing_here)) }
        items(groups, key = { it.id }) { g ->
            ListCard(g.name, stringResource(R.string.group_members_count, g.members), onClick = { openGroup(g.id) }) {
                Icon(Icons.Outlined.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (merging) {
        MergeDialog(
            groups = groups,
            deviceGroups = deviceGroups,
            onDismiss = { merging = false },
        ) { name, ids, phone ->
            merging = false
            vm.merge(name, ids, phone) { id, count, repeated ->
                Toast.makeText(context, String.format(mergedMsg, count, repeated), Toast.LENGTH_LONG).show()
                openGroup(id)
            }
        }
    }
    if (adding) {
        TextInputDialog(
            title = stringResource(R.string.group_new),
            label = stringResource(R.string.group_name),
            onDismiss = { adding = false },
            onSave = { name, _ ->
                adding = false
                vm.create(name) { openGroup(it) }
            },
        )
    }
}

// ---------------------------------------------------------------------------------- one group

@Composable
fun GroupScreen(onBack: () -> Unit, pickContacts: (Long) -> Unit, vm: GroupsViewModel = hiltViewModel()) {
    val group by vm.group.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val visible by vm.visibleMembers.collectAsStateWithLifecycle()
    val memberQuery by vm.memberQuery.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cleanedMsg = stringResource(R.string.group_cleaned)
    var renaming by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = group?.name.orEmpty(),
        onBack = onBack,
        floating = { AddFab(stringResource(R.string.group_from_contacts)) { pickContacts(vm.groupId) } },
    ) {
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { pasting = true }) {
                    Icon(Icons.Outlined.ContentPaste, null)
                    Text(stringResource(R.string.group_paste), modifier = Modifier.padding(start = 6.dp))
                }
                FilledTonalButton(onClick = { renaming = true }) {
                    Icon(Icons.Outlined.Edit, null)
                    Text(stringResource(R.string.group_rename), modifier = Modifier.padding(start = 6.dp))
                }
                if (members.size > 1) {
                    FilledTonalButton(
                        enabled = !working,
                        onClick = {
                            vm.cleanUp { removed -> Toast.makeText(context, String.format(cleanedMsg, removed), Toast.LENGTH_SHORT).show() }
                        },
                    ) {
                        Icon(Icons.Outlined.CleaningServices, null)
                        Text(stringResource(R.string.group_clean), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            if (members.size > 8) {
                OutlinedTextField(
                    value = memberQuery,
                    onValueChange = { vm.memberQuery.value = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text(stringResource(R.string.group_filter)) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
            Text(
                stringResource(R.string.group_members_count, members.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        if (members.isEmpty()) item { EmptyHint(stringResource(R.string.group_empty)) }
        items(visible, key = { it.address }) { m ->
            ListCard(m.name ?: m.address, if (m.name != null) m.address else null) { RemoveButton { vm.removeMember(m) } }
        }
        item {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 16.dp)) {
                Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.group_delete), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }

    if (renaming) {
        TextInputDialog(
            title = stringResource(R.string.group_rename),
            label = stringResource(R.string.group_name),
            initial = group?.name.orEmpty(),
            onDismiss = { renaming = false },
            onSave = { name, _ -> renaming = false; vm.rename(name) },
        )
    }
    if (pasting) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text(stringResource(R.string.group_paste)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.group_paste_hint)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { pasting = false; vm.addPasted(text) }) { Text(stringResource(R.string.add)) } },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.group_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---------------------------------------------------------------------------------- picker

@Composable
fun GroupPickerScreen(onBack: () -> Unit, vm: GroupsViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val picked by vm.picked.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(entries) { if (query.isBlank()) vm.preparePicker(entries) }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
                title = { Text(stringResource(R.string.group_picked, picked.size)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.savePicked(onBack) },
                icon = { Icon(Icons.Outlined.Check, null) },
                text = { Text(stringResource(R.string.save)) },
                shape = RoundedCornerShape(18.dp),
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .height(50.dp)
                    .clip(RoundedCornerShape(25.dp))
                    .background(scheme.surfaceContainer)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.Search, null, tint = scheme.onSurfaceVariant)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.contacts_search_hint), color = scheme.onSurfaceVariant)
                    BasicTextField(
                        value = query,
                        onValueChange = { vm.query.value = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, textDirection = TextDirection.Content),
                        cursorBrush = SolidColor(scheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Icon(Icons.Outlined.Contacts, null, tint = scheme.onSurfaceVariant)
            }
            ContactList(
                entries = entries,
                sectioned = query.isBlank(),
                onClick = vm::toggle,
                selected = picked,
            )
        }
    }
}

// ---------------------------------------------------------------------------------- merge

/** Pick groups (Barq's and the phone's) to merge into one new group without repeated numbers. */
@Composable
private fun MergeDialog(
    groups: List<SendGroupWithCount>,
    deviceGroups: List<PhoneGroup>,
    onDismiss: () -> Unit,
    onMerge: (String, Set<Long>, List<PhoneGroup>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var barq by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var phone by remember { mutableStateOf<Set<String>>(emptySet()) }
    val f = filter.trim()
    val shownBarq = groups.filter { f.isEmpty() || it.name.contains(f, ignoreCase = true) }
    val shownPhone = deviceGroups.filter { f.isEmpty() || it.title.contains(f, ignoreCase = true) }
    val allIds = groups.map { it.id }.toSet()
    val allTitles = deviceGroups.map { it.title }.toSet()
    val everything = barq.size == allIds.size && phone.size == allTitles.size
    val count = barq.size + phone.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_merge)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.group_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (groups.size + deviceGroups.size > 6) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        placeholder = { Text(stringResource(R.string.group_filter)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                CheckLine(stringResource(R.string.groups_all), everything) {
                    if (everything) { barq = emptySet(); phone = emptySet() } else { barq = allIds; phone = allTitles }
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shownBarq, key = { "b${it.id}" }) { g ->
                        CheckLine(stringResource(R.string.group_chip, g.name, g.members), g.id in barq) {
                            barq = if (g.id in barq) barq - g.id else barq + g.id
                        }
                    }
                    items(shownPhone, key = { "p${it.title}" }) { g ->
                        CheckLine(stringResource(R.string.group_chip_phone, g.title, g.members), g.title in phone) {
                            phone = if (g.title in phone) phone - g.title else phone + g.title
                        }
                    }
                }
                Text(
                    stringResource(R.string.group_merge_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && count > 0,
                onClick = { onMerge(name, barq, deviceGroups.filter { it.title in phone }) },
            ) { Text(stringResource(R.string.group_merge_do)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CheckLine(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
