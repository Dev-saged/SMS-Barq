package dev.saged.barq.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.saged.barq.data.contacts.ContactEntry
import dev.saged.barq.data.db.Category
import kotlinx.coroutines.launch

/**
 * The phone's contacts, however many: letter sections that stay pinned at the top while
 * scrolling, and the interactive [AlphabetBar] to jump between letters. With [sectioned] false
 * (search results) it is a plain list. [selected] turns on multi-select (group picker).
 *
 * [headerCount] is the number of items [header] adds, so the letter bar can jump precisely.
 */
@Composable
fun ContactList(
    entries: List<ContactEntry>,
    sectioned: Boolean,
    onClick: (ContactEntry) -> Unit,
    modifier: Modifier = Modifier,
    selected: Set<String>? = null,
    headerCount: Int = 0,
    header: LazyListScope.() -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val sections = remember(entries, sectioned) {
        if (sectioned) entries.groupBy { it.section }.toList() else emptyList()
    }
    val positions = remember(sections, headerCount) {
        val map = LinkedHashMap<String, Int>()
        var index = headerCount
        sections.forEach { (letter, group) ->
            map[letter] = index
            index += 1 + group.size
        }
        map
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = if (sectioned) 34.dp else 8.dp, bottom = 16.dp),
        ) {
            header()
            if (sectioned) {
                sections.forEach { (letter, group) ->
                    stickyHeader(key = "section_$letter", contentType = "section") { SectionHeader(letter) }
                    items(group, key = { it.key }, contentType = { "contact" }) { e ->
                        ContactRow(e, selected?.contains(e.key), onClick)
                    }
                }
            } else {
                items(entries, key = { it.key }, contentType = { "contact" }) { e ->
                    ContactRow(e, selected?.contains(e.key), onClick)
                }
            }
        }
        if (sectioned) {
            AlphabetBar(
                letters = sections.map { it.first },
                onLetter = { letter ->
                    positions[letter]?.let { scope.launch { listState.scrollToItem(it) } }
                },
                modifier = Modifier.align(AbsoluteAlignment.CenterLeft).padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(letter: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 18.dp, top = 10.dp, bottom = 4.dp),
    ) {
        Text(
            letter,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
fun ContactRow(entry: ContactEntry, selected: Boolean?, onClick: (ContactEntry) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected == true) scheme.secondaryContainer else scheme.background)
            .clickable { onClick(entry) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(
            name = entry.name,
            address = entry.number,
            seed = entry.contactId,
            category = Category.PERSONAL,
            size = 44.dp,
            photoUri = entry.photoUri,
        )
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.number,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
                entry.typeLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                    )
                }
            }
        }
        if (selected != null) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (selected) scheme.primary else scheme.outline,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
