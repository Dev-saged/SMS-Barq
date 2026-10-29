package dev.saged.barq.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import dev.saged.barq.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Reply with a message" from the incoming-call screen. Required for the SMS role. */
@AndroidEntryPoint
class HeadlessSmsSendService : Service() {

    @Inject lateinit var engine: SendEngine
    @Inject @field:AppScope lateinit var scope: CoroutineScope

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val address = intent?.data?.schemeSpecificPart?.substringBefore('?')
        if (!text.isNullOrBlank() && !address.isNullOrBlank()) {
            val recipients = address.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            scope.launch {
                // Same SIM rules as everywhere else (conversation SIM → Barq default → phone default).
                runCatching { engine.sendNew(recipients, text, subId = null, allowService = false) }
                stopSelf(startId)
            }
        } else stopSelf(startId)
        return START_NOT_STICKY
    }
}
