package dev.saged.barq.ui

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.provider.Telephony
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.saged.barq.data.ConversationRepository
import dev.saged.barq.data.ProviderWatcher
import dev.saged.barq.data.SyncManager
import dev.saged.barq.data.contacts.ContactsWatcher
import dev.saged.barq.data.settings.AppSettings
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.sms.SendEngine
import dev.saged.barq.util.CrashLog
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
    private val conversations: ConversationRepository,
    private val engine: SendEngine,
    private val contactsWatcher: ContactsWatcher,
    private val providerWatcher: ProviderWatcher,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val syncProgress = sync.progress

    private val _isDefault = MutableStateFlow(isDefaultSmsApp())
    val isDefault: StateFlow<Boolean> = _isDefault

    /** True once after a crash: the inbox offers to open the crash log. */
    val crashPending = MutableStateFlow(CrashLog.hasUnseenCrash())

    fun isDefaultSmsApp(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

    /**
     * Every time the app comes to the foreground: pick up messages other apps wrote (cheap when
     * there are none) and resume the send queue.
     */
    fun onAppVisible() {
        _isDefault.value = isDefaultSmsApp()
        viewModelScope.launch {
            sync.sync()
            // Once per launch, every failed message is checked again against the system store:
            // one that went out after all (sent by another app) is shown as sent.
            sync.reconcile(repairFailed = !repairedThisLaunch)
            repairedThisLaunch = true
            if (_isDefault.value) engine.kick()
        }
    }

    fun onPermissionsChanged() {
        _isDefault.value = isDefaultSmsApp()
        contactsWatcher.start()
        providerWatcher.start()
        viewModelScope.launch {
            conversations.refreshNames(rebuildIndex = true)
            sync.sync()
        }
    }

    /** The conversation for a number opened from another app (contacts, call log, sms: link). */
    suspend fun conversationFor(address: String, draft: String?): Long =
        conversations.openOrCreate(address, draft)

    fun dismissCrash() {
        CrashLog.markSeen()
        crashPending.value = false
    }

    private companion object {
        @Volatile var repairedThisLaunch = false
    }

    fun finishOnboarding() {
        viewModelScope.launch { settingsRepository.setOnboardingDone(true) }
    }
}
