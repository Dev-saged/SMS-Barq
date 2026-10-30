package dev.saged.barq.ui.starred

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.R
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.db.StarredMessage
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.ui.components.TimeFormat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StarredViewModel @Inject constructor(
    private val messages: MessageDao,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val starred = messages.observeStarred().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun unstar(id: Long) = viewModelScope.launch { messages.setStarred(id, false) }
}

/** Every starred message in one place; a tap opens it in its conversation. */
@Composable
fun StarredScreen(onBack: () -> Unit, openAt: (Long, Long) -> Unit, vm: StarredViewModel = hiltViewModel()) {
    val list by vm.starred.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.starred_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
    ) { padding ->
        val items = list
        if (items != null && items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(84.dp).clip(RoundedCornerShape(28.dp)).background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.StarOutline, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(40.dp)) }
                Text(
                    stringResource(R.string.starred_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items.orEmpty(), key = { it.message.id }) { s -> StarredRow(s, settings) { openAt(s.message.threadId, s.message.id) } }
        }
    }
}

@Composable
private fun StarredRow(s: StarredMessage, settings: AppSettings, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val m = s.message
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerLow)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Star, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
            Text(
                s.displayName ?: m.address,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(TimeFormat.full(m.date, settings), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Text(
            m.body,
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
