package dev.saged.barq

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.fragment.app.FragmentActivity
import dev.saged.barq.ui.lock.AppLock
import dev.saged.barq.ui.lock.LockScreen
import dev.saged.barq.ui.whatsnew.WhatsNewHost
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.saged.barq.data.contacts.ContactsRepository
import dev.saged.barq.data.update.UpdateManager
import dev.saged.barq.ui.update.UpdateLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.saged.barq.domain.PhoneUtils
import dev.saged.barq.ui.BarqNavHost
import dev.saged.barq.ui.components.LocalContacts
import dev.saged.barq.ui.MainViewModel
import dev.saged.barq.ui.Routes
import dev.saged.barq.ui.theme.BarqTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var contacts: ContactsRepository
    @Inject lateinit var updates: UpdateManager

    /** Route requested by an external intent (notification tap, sms: link, share). */
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val locked by AppLock.locked.collectAsStateWithLifecycle()
            val s = settings
            if (s == null) {
                Box(Modifier.fillMaxSize())
            } else {
                LaunchedEffect(s.lockEnabled) {
                    if (!s.lockEnabled) AppLock.onForeground(false, 0)
                    // The recent-apps preview hides Barq's messages while the lock is on (Android 13+).
                    if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!s.lockEnabled)
                }
                BarqTheme(s) {
                    CompositionLocalProvider(LocalContacts provides contacts) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            BarqNavHost(
                                startOnboarding = !s.onboardingDone,
                                pendingRoute = pendingRoute,
                                mainViewModel = viewModel,
                            )
                            UpdateLayer(updates)
                            if (s.lockEnabled && locked && s.onboardingDone) {
                                BackHandler { moveTaskToBack(true) }
                                LockScreen(s.lockMethod) {
                                    AppLock.prompt(this@MainActivity, s.lockMethod) { ok -> if (ok) AppLock.open() }
                                }
                            } else if (s.onboardingDone) {
                                WhatsNewHost(viewModel)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.settings.value?.let { AppLock.onForeground(it.lockEnabled, it.lockAfterSec) }
        viewModel.onAppVisible()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) AppLock.onBackground()
    }

    // Barq opening a picker, a share sheet or a link is not "leaving": the lock gives a short grace.
    override fun startActivity(intent: Intent?, options: Bundle?) {
        AppLock.leavingForPicker()
        super.startActivity(intent, options)
    }

    @Deprecated("Called by the activity-result APIs")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        AppLock.leavingForPicker()
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1)
        when {
            intent.action == ACTION_INSTALL_UPDATE -> lifecycleScope.launch {
                // A cold start from the "downloaded" notification: find the saved file first.
                withContext(Dispatchers.IO) { updates.restore() }
                updates.install(this@MainActivity)
            }
            intent.action == ACTION_OPEN_UPDATE -> updates.showSheet(true)
            threadId >= 0 -> pendingRoute.value = Routes.thread(threadId)
            intent.action == ACTION_UNREAD -> {
                viewModel.requestOpenUnread()
                pendingRoute.value = Routes.INBOX
            }
            intent.action == Intent.ACTION_SENDTO ||
                (intent.action == Intent.ACTION_VIEW && intent.data?.scheme in SMS_SCHEMES) ||
                (intent.action == Intent.ACTION_SEND && intent.data != null) -> {
                val to = intent.data?.let(::recipientsFrom).orEmpty()
                val body = intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val recipients = PhoneUtils.splitAddresses(to)
                if (recipients.size == 1) {
                    // "Message" from contacts or the call log: straight into that person's conversation.
                    lifecycleScope.launch {
                        val tid = runCatching { viewModel.conversationFor(recipients.first(), body) }.getOrNull()
                        pendingRoute.value = if (tid != null) Routes.thread(tid) else Routes.compose(to, body)
                    }
                } else {
                    pendingRoute.value = Routes.compose(to, body)
                }
            }
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" -> {
                pendingRoute.value = Routes.compose("", intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            }
        }
    }

    private fun recipientsFrom(uri: Uri): String =
        Uri.decode(uri.schemeSpecificPart.orEmpty()).substringBefore('?').replace(';', ',')

    companion object {
        const val EXTRA_THREAD_ID = "thread_id"

        /** Home-screen shortcut: open the inbox on its "Unread" view. */
        const val ACTION_UNREAD = "dev.saged.barq.action.UNREAD"
        const val ACTION_INSTALL_UPDATE = "dev.saged.barq.action.INSTALL_UPDATE"
        const val ACTION_OPEN_UPDATE = "dev.saged.barq.action.OPEN_UPDATE"
        private val SMS_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")
    }
}
