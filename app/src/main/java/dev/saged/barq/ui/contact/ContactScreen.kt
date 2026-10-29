package dev.saged.barq.ui.contact

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.saged.barq.R
import dev.saged.barq.data.contacts.ContactInfo
import dev.saged.barq.data.contacts.ContactsRepository
import dev.saged.barq.data.db.ConversationDao
import dev.saged.barq.data.db.ConversationEntity
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.ui.components.Avatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ContactUi(val conversation: ConversationEntity, val contact: ContactInfo?) {
    val address: String get() = PhoneUtils.splitAddresses(conversation.addresses).firstOrNull().orEmpty()
    val name: String get() = contact?.name ?: conversation.displayName
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContactViewModel @Inject constructor(
    savedState: SavedStateHandle,
    conversations: ConversationDao,
    private val contacts: ContactsRepository,
) : ViewModel() {
    private val threadId: Long = savedState.get<Long>("threadId") ?: -1L

    /** Re-reads the contact whenever the phone's contacts change. */
    val state: StateFlow<ContactUi?> =
        combine(conversations.observe(threadId), contacts.version) { conv, _ ->
            conv?.let { ContactUi(it, PhoneUtils.splitAddresses(it.addresses).firstOrNull()?.let(contacts::lookup)) }
        }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun ContactScreen(
    onBack: () -> Unit,
    openThread: (Long) -> Unit,
    vm: ContactViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val copiedMsg = stringResource(R.string.number_copied)

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
    ) { padding ->
        val ui = state ?: return@Scaffold
        val canCall = PhoneUtils.isPhoneNumber(ui.address)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .size(148.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary)))
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Avatar(
                    name = ui.name,
                    address = ui.conversation.addresses,
                    seed = ui.conversation.threadId,
                    category = ui.conversation.category,
                    size = 140.dp,
                    photoUri = ui.contact?.photoUri,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                ui.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))

            // Tap the number to copy it — no extra button.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("number", ui.address))
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .background(scheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    ui.address,
                    style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr),
                    letterSpacing = 1.sp,
                    color = scheme.onSurface,
                )
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.tap_number_to_copy),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(32.dp))

            if (canCall) {
                CallButton {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(ui.address))))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { openThread(ui.conversation.threadId) },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Outlined.Sms, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.messages_button))
                }
                if (canCall) {
                    val contact = ui.contact
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = if (contact != null) {
                                    Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(contact.contactId, contact.lookupKey))
                                } else {
                                    Intent(ContactsContract.Intents.Insert.ACTION)
                                        .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                                        .putExtra(ContactsContract.Intents.Insert.PHONE, ui.address)
                                }
                                context.startActivity(intent)
                            }
                        },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Icon(if (contact != null) Icons.Outlined.PersonOutline else Icons.Outlined.PersonAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(if (contact != null) R.string.open_contact else R.string.add_contact))
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** The signature call button: a wide glowing gradient pill. */
@Composable
private fun CallButton(onClick: () -> Unit) {
    val green = Color(0xFF1FA35B)
    val greenDark = Color(0xFF0E7A40)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .shadow(14.dp, RoundedCornerShape(32.dp), ambientColor = green, spotColor = green)
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.horizontalGradient(listOf(green, greenDark)))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Call, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.size(12.dp))
        Text(
            stringResource(R.string.call),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
