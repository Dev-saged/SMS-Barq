package dev.saged.barq.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.R
import dev.saged.barq.data.BlockList
import dev.saged.barq.data.db.AutoReplyDao
import dev.saged.barq.data.db.AutoReplyRuleEntity
import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.CategoryRuleEntity
import dev.saged.barq.data.db.RuleDao
import dev.saged.barq.data.db.TemplateDao
import dev.saged.barq.data.db.TemplateEntity
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.AutoReplyMode
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.ui.components.icon
import dev.saged.barq.ui.components.label
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val blockList: BlockList,
    private val rules: RuleDao,
    private val templates: TemplateDao,
    private val autoReply: AutoReplyDao,
    val settingsRepo: SettingsRepository,
) : ViewModel() {
    private fun <T> kotlinx.coroutines.flow.Flow<List<T>>.state() = stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val numbers = blockList.numbers.state()
    val keywords = blockList.keywords.state()
    val ruleList = rules.observe().state()
    val templateList = templates.observe().state()
    val autoReplyRules = autoReply.observeRules().state()
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private fun io(block: suspend () -> Unit) = viewModelScope.launch { block() }

    fun blockNumber(n: String) = io { blockList.blockNumber(n) }
    fun unblockNumber(e: dev.saged.barq.data.db.BlockedNumberEntity) = io { blockList.unblockNumber(e) }
    fun blockKeyword(k: String) = io { blockList.blockKeyword(k) }
    fun unblockKeyword(e: dev.saged.barq.data.db.BlockedKeywordEntity) = io { blockList.unblockKeyword(e) }

    fun saveRule(sender: String, category: Category) = io {
        if (sender.isNotBlank()) rules.upsert(CategoryRuleEntity(sender = sender.trim(), category = category))
    }
    fun deleteRule(id: Long) = io { rules.delete(id) }

    fun saveTemplate(t: TemplateEntity?, text: String) = io {
        if (text.isBlank()) return@io
        templates.upsert(t?.copy(text = text.trim()) ?: TemplateEntity(text = text.trim(), position = templateList.value.size))
    }
    fun deleteTemplate(id: Long) = io { templates.delete(id) }

    fun saveAutoRule(rule: AutoReplyRuleEntity?, keywords: String, reply: String) = io {
        if (keywords.isBlank() || reply.isBlank()) return@io
        autoReply.upsertRule(
            rule?.copy(keywords = keywords.trim(), reply = reply.trim())
                ?: AutoReplyRuleEntity(keywords = keywords.trim(), reply = reply.trim())
        )
    }
    fun toggleAutoRule(rule: AutoReplyRuleEntity) = io { autoReply.upsertRule(rule.copy(enabled = !rule.enabled)) }
    fun deleteAutoRule(id: Long) = io { autoReply.deleteRule(id) }

    fun update(block: suspend SettingsRepository.() -> Unit) = io { settingsRepo.block() }
}

// ---------------------------------------------------------------------------------- shared

@Composable
internal fun ListCard(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

@Composable
internal fun RemoveButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Outlined.Close, stringResource(R.string.remove)) }
}

@Composable
internal fun EmptyHint(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 8.dp),
    )
}

@Composable
internal fun AddFab(label: String, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Outlined.Add, null) },
        text = { Text(label) },
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    )
}

@Composable
internal fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    secondLabel: String? = null,
    secondInitial: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var first by remember { mutableStateOf(initial) }
    var second by remember { mutableStateOf(secondInitial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = first, onValueChange = { first = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                if (secondLabel != null) {
                    OutlinedTextField(
                        value = second, onValueChange = { second = it }, label = { Text(secondLabel) },
                        minLines = 2, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(first, second) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---------------------------------------------------------------------------------- blocked

@Composable
fun BlockedScreen(onBack: () -> Unit, vm: ToolsViewModel = hiltViewModel()) {
    val numbers by vm.numbers.collectAsStateWithLifecycle()
    val keywords by vm.keywords.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.blocked_title),
        onBack = onBack,
        floating = { AddFab(stringResource(if (tab == 0) R.string.block_number else R.string.block_keyword)) { adding = true } },
    ) {
        item {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.numbers)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.keywords)) })
            }
            Text(
                stringResource(if (tab == 0) R.string.blocked_numbers_hint else R.string.blocked_keywords_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            )
        }
        if (tab == 0) {
            if (numbers.isEmpty()) item { EmptyHint(stringResource(R.string.nothing_here)) }
            items(numbers, key = { it.number }) { n -> ListCard(n.number) { RemoveButton { vm.unblockNumber(n) } } }
        } else {
            if (keywords.isEmpty()) item { EmptyHint(stringResource(R.string.nothing_here)) }
            items(keywords, key = { it.keyword }) { k -> ListCard(k.keyword) { RemoveButton { vm.unblockKeyword(k) } } }
        }
    }

    if (adding) {
        TextInputDialog(
            title = stringResource(if (tab == 0) R.string.block_number else R.string.block_keyword),
            label = stringResource(if (tab == 0) R.string.number else R.string.keyword),
            onDismiss = { adding = false },
            onSave = { v, _ ->
                adding = false
                if (tab == 0) vm.blockNumber(v) else vm.blockKeyword(v)
            },
        )
    }
}

// ---------------------------------------------------------------------------------- rules

@Composable
fun RulesScreen(onBack: () -> Unit, vm: ToolsViewModel = hiltViewModel()) {
    val rules by vm.ruleList.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.rules_title),
        onBack = onBack,
        floating = { AddFab(stringResource(R.string.add_rule)) { adding = true } },
    ) {
        item {
            Text(
                stringResource(R.string.rules_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            )
        }
        if (rules.isEmpty()) item { EmptyHint(stringResource(R.string.nothing_here)) }
        items(rules, key = { it.id }) { r ->
            ListCard(r.sender, r.category.label()) { RemoveButton { vm.deleteRule(r.id) } }
        }
    }

    if (adding) {
        var sender by remember { mutableStateOf("") }
        var category by remember { mutableStateOf(Category.BANK) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.add_rule)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = sender, onValueChange = { sender = it },
                        label = { Text(stringResource(R.string.sender_label)) },
                        supportingText = { Text(stringResource(R.string.sender_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Category.entries.forEach { c ->
                            FilterChip(
                                selected = c == category,
                                onClick = { category = c },
                                label = { Text(c.label()) },
                                leadingIcon = { Icon(c.icon(), null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { adding = false; vm.saveRule(sender, category) }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---------------------------------------------------------------------------------- templates

@Composable
fun TemplatesScreen(onBack: () -> Unit, vm: ToolsViewModel = hiltViewModel()) {
    val templates by vm.templateList.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TemplateEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.templates_title),
        onBack = onBack,
        floating = { AddFab(stringResource(R.string.add_template)) { adding = true } },
    ) {
        item {
            Text(
                stringResource(R.string.templates_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            )
        }
        if (templates.isEmpty()) item { EmptyHint(stringResource(R.string.nothing_here)) }
        items(templates, key = { it.id }) { t ->
            ListCard(t.text, onClick = { editing = t }) { RemoveButton { vm.deleteTemplate(t.id) } }
        }
    }

    val current = editing
    if (adding || current != null) {
        TextInputDialog(
            title = stringResource(if (current != null) R.string.edit_template else R.string.add_template),
            label = stringResource(R.string.template_text),
            initial = current?.text.orEmpty(),
            onDismiss = { adding = false; editing = null },
            onSave = { v, _ ->
                vm.saveTemplate(current, v)
                adding = false
                editing = null
            },
        )
    }
}

// ---------------------------------------------------------------------------------- auto reply

@Composable
fun AutoReplyScreen(onBack: () -> Unit, vm: ToolsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val rules by vm.autoReplyRules.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AutoReplyRuleEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf<Boolean?>(null) } // true = from, false = to
    var cooldownDialog by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.auto_reply_title),
        onBack = onBack,
        floating = {
            if (s.autoReplyMode == AutoReplyMode.SMART) AddFab(stringResource(R.string.add_smart_reply)) { adding = true }
        },
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (s.autoReplyEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { vm.update { setAutoReplyEnabled(!s.autoReplyEnabled) } }
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Outlined.SmartToy, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_reply_switch), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.auto_reply_switch_sub), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = s.autoReplyEnabled, onCheckedChange = { v -> vm.update { setAutoReplyEnabled(v) } })
            }
        }
        item {
            SettingsGroup(stringResource(R.string.auto_reply_mode)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                    SegmentedButton(
                        selected = s.autoReplyMode == AutoReplyMode.FIXED,
                        onClick = { vm.update { setAutoReplyMode(AutoReplyMode.FIXED) } },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(stringResource(R.string.mode_fixed)) }
                    SegmentedButton(
                        selected = s.autoReplyMode == AutoReplyMode.SMART,
                        onClick = { vm.update { setAutoReplyMode(AutoReplyMode.SMART) } },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(stringResource(R.string.mode_smart)) }
                }
                Text(
                    stringResource(if (s.autoReplyMode == AutoReplyMode.FIXED) R.string.mode_fixed_sub else R.string.mode_smart_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                RowDivider()
                ClickRow(Icons.Outlined.Edit, stringResource(R.string.fixed_reply_text), s.autoReplyText) { editingText = true }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.auto_reply_conditions)) {
                SwitchRow(
                    Icons.Outlined.Contacts, stringResource(R.string.contacts_only), stringResource(R.string.contacts_only_sub),
                    s.autoReplyContactsOnly,
                ) { v -> vm.update { setAutoReplyContactsOnly(v) } }
                RowDivider()
                ClickRow(Icons.Outlined.Timer, stringResource(R.string.cooldown), stringResource(R.string.cooldown_value, s.autoReplyCooldownMin)) {
                    cooldownDialog = true
                }
                RowDivider()
                SwitchRow(
                    Icons.Outlined.Bedtime, stringResource(R.string.time_window),
                    stringResource(R.string.time_window_value, hm(s.autoReplyFromMin), hm(s.autoReplyToMin)),
                    s.autoReplyWindow,
                ) { v -> vm.update { setAutoReplyWindow(v) } }
                if (s.autoReplyWindow) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = { pickTime = true }) { Text(stringResource(R.string.from_time, hm(s.autoReplyFromMin))) }
                        TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.to_time, hm(s.autoReplyToMin))) }
                    }
                }
            }
            Text(
                stringResource(R.string.auto_reply_safety),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
        if (s.autoReplyMode == AutoReplyMode.SMART) {
            item {
                Text(
                    stringResource(R.string.smart_rules),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            items(rules, key = { it.id }) { r ->
                ListCard(
                    title = r.reply,
                    subtitle = stringResource(R.string.when_contains, r.keywords.replace("|", " · ")),
                    onClick = { editing = r },
                ) {
                    Switch(checked = r.enabled, onCheckedChange = { vm.toggleAutoRule(r) })
                    RemoveButton { vm.deleteAutoRule(r.id) }
                }
            }
        }
    }

    if (editingText) {
        TextInputDialog(
            title = stringResource(R.string.fixed_reply_text),
            label = stringResource(R.string.reply_text),
            initial = s.autoReplyText,
            onDismiss = { editingText = false },
            onSave = { v, _ ->
                editingText = false
                if (v.isNotBlank()) vm.update { setAutoReplyText(v.trim()) }
            },
        )
    }
    val currentRule = editing
    if (adding || currentRule != null) {
        TextInputDialog(
            title = stringResource(R.string.add_smart_reply),
            label = stringResource(R.string.keywords_label),
            initial = currentRule?.keywords.orEmpty(),
            secondLabel = stringResource(R.string.reply_text),
            secondInitial = currentRule?.reply.orEmpty(),
            onDismiss = { adding = false; editing = null },
            onSave = { k, r ->
                vm.saveAutoRule(currentRule, k, r)
                adding = false
                editing = null
            },
        )
    }
    if (cooldownDialog) {
        val options = listOf(15, 30, 60, 180, 720, 1440).map { it to stringResource(R.string.cooldown_value, it) }
        ChoiceDialog(stringResource(R.string.cooldown), options, s.autoReplyCooldownMin, { cooldownDialog = false }) { v ->
            cooldownDialog = false
            vm.update { setAutoReplyCooldown(v) }
        }
    }
    pickTime?.let { isFrom -> key(isFrom) {
        val minutes = if (isFrom) s.autoReplyFromMin else s.autoReplyToMin
        val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = null },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    val v = state.hour * 60 + state.minute
                    pickTime = null
                    vm.update { if (isFrom) setAutoReplyFrom(v) else setAutoReplyTo(v) }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = null }) { Text(stringResource(R.string.cancel)) } },
        )
    } }
}

private fun hm(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
