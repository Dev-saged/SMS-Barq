package dev.saged.barq.ui

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.provider.Telephony
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.data.MessageRepository
import dev.saged.barq.data.SyncManager
import dev.saged.barq.data.contacts.ContactsWatcher
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.sms.SendQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val sync: SyncManager,
    private val repository: MessageRepository,
    private val sendQueue: SendQueue,
    private val contactsWatcher: ContactsWatcher,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val syncProgress = sync.progress

    private val _isDefault = MutableStateFlow(isDefaultSmsApp())
    val isDefault: StateFlow<Boolean> = _isDefault

    fun isDefaultSmsApp(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

    /** Every time the app comes to the foreground: pick up new history, resume the send queue. */
    fun onAppVisible() {
        _isDefault.value = isDefaultSmsApp()
        viewModelScope.launch {
            sync.sync()
            if (_isDefault.value) sendQueue.kick()
        }
    }

    fun onPermissionsChanged() {
        _isDefault.value = isDefaultSmsApp()
        contactsWatcher.start()
        viewModelScope.launch {
            repository.refreshNames()
            sync.sync()
        }
    }

    fun finishOnboarding() {
        viewModelScope.launch { settingsRepository.setOnboardingDone(true) }
    }
}
