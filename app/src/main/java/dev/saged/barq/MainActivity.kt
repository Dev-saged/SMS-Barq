package dev.saged.barq

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.saged.barq.ui.BarqNavHost
import dev.saged.barq.ui.MainViewModel
import dev.saged.barq.ui.Routes
import dev.saged.barq.ui.theme.BarqTheme
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Route requested by an external intent (notification tap, sms: link, share). */
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val s = settings
            if (s == null) {
                Box(Modifier.fillMaxSize())
            } else {
                BarqTheme(s) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        BarqNavHost(
                            startOnboarding = !s.onboardingDone,
                            pendingRoute = pendingRoute,
                            mainViewModel = viewModel,
                        )
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
        viewModel.onAppVisible()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1)
        when {
            threadId >= 0 -> pendingRoute.value = Routes.thread(threadId)
            intent.action == Intent.ACTION_SENDTO || (intent.action == Intent.ACTION_SEND && intent.data != null) -> {
                val to = intent.data?.let(::recipientsFrom).orEmpty()
                val body = intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                pendingRoute.value = Routes.compose(to, body)
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
    }
}
