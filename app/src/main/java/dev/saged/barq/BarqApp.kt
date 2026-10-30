package dev.saged.barq

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.saged.barq.data.ProviderWatcher
import dev.saged.barq.data.contacts.ContactsWatcher
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.sms.SendQueue
import dev.saged.barq.data.db.AutoReplyDao
import dev.saged.barq.data.db.TemplateDao
import dev.saged.barq.data.db.TemplateEntity
import dev.saged.barq.di.AppScope
import dev.saged.barq.domain.AutoReplyEngine
import dev.saged.barq.notifications.Notifier
import dev.saged.barq.util.CrashLog
import dev.saged.barq.work.MaintenanceScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Barq — fast, clean SMS.
 * © Sajed Al-Abadla (ساجد العبادلة). All rights reserved.
 */
@HiltAndroidApp
class BarqApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var maintenance: MaintenanceScheduler
    @Inject lateinit var templates: TemplateDao
    @Inject lateinit var autoReply: AutoReplyDao
    @Inject @field:AppScope lateinit var scope: CoroutineScope
    @Inject lateinit var contactsWatcher: ContactsWatcher
    @Inject lateinit var providerWatcher: ProviderWatcher
    @Inject lateinit var messages: MessageDao
    @Inject lateinit var sendQueue: SendQueue
    @Inject lateinit var settings: dev.saged.barq.data.settings.SettingsRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        // First, so even a crash while starting up is recorded.
        CrashLog.install(this)
        super.onCreate()
        notifier.createChannels()
        maintenance.ensureScheduled()
        contactsWatcher.start()
        providerWatcher.start()
        scope.launch {
            seedDefaults()
            resumeQueue()
            // The local gateway was left on: bring it back (Android may refuse while in the background).
            if (runCatching { settings.current().gatewayEnabled }.getOrDefault(false)) {
                dev.saged.barq.sms.gateway.GatewayService.start(this@BarqApp)
            }
        }
    }

    /**
     * Android may have stopped Barq in the middle of a send: whatever is still waiting goes on
     * by itself the next time Barq's process starts, without the user doing anything.
     */
    private suspend fun resumeQueue() {
        val now = System.currentTimeMillis()
        runCatching {
            if (messages.dueQueued(now, 1, 0L).isNotEmpty()) sendQueue.kick()
            messages.nextRetryAt(now)?.let { sendQueue.kickAt(it) }
        }.onFailure { CrashLog.warn("resume-queue", it) }
    }

    private suspend fun seedDefaults() {
        if (templates.count() == 0) {
            resources.getStringArray(R.array.default_templates).toList()
                .forEachIndexed { i, t -> templates.upsert(TemplateEntity(text = t, position = i)) }
        }
        if (autoReply.ruleCount() == 0) {
            AutoReplyEngine.defaultRules.forEach { autoReply.upsertRule(it) }
        }
    }
}
