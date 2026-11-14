package dev.saged.barq

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.saged.barq.data.ProviderWatcher
import dev.saged.barq.data.contacts.ContactsWatcher
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.sms.SendQueue
import dev.saged.barq.data.db.TemplateDao
import dev.saged.barq.data.db.TemplateEntity
import dev.saged.barq.di.AppScope
import dev.saged.barq.domain.ReplyEngine
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
    @Inject lateinit var replyEngine: ReplyEngine
    @Inject @field:AppScope lateinit var scope: CoroutineScope
    @Inject lateinit var contactsWatcher: ContactsWatcher
    @Inject lateinit var providerWatcher: ProviderWatcher
    @Inject lateinit var messages: MessageDao
    @Inject lateinit var sendQueue: SendQueue
    @Inject lateinit var settings: dev.saged.barq.data.settings.SettingsRepository
    @Inject lateinit var conversations: dev.saged.barq.data.ConversationRepository
    @Inject lateinit var snapshot: dev.saged.barq.data.backup.SettingsSnapshot
    @Inject lateinit var updates: dev.saged.barq.data.update.UpdateManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(dev.saged.barq.util.LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        // First, so even a crash while starting up is recorded.
        CrashLog.install(this)
        super.onCreate()
        notifier.createChannels()
        maintenance.ensureScheduled()
        contactsWatcher.start()
        providerWatcher.start()
        publishShortcuts()
        scope.launch {
            // A new phone or a reinstall: Android may have brought back Barq's lists and settings.
            // Applied first, so the default templates are not added next to the user's own.
            runCatching { snapshot.restoreIfNew() }.onFailure { CrashLog.warn("snapshot", it) }
            seedDefaults()
            resumeQueue()
            // An update installed meanwhile is deleted at once; one still waiting shows as ready.
            runCatching { updates.restore() }.onFailure { CrashLog.warn("update-restore", it) }
            // 1.5.0: contacts are always personal — fix conversations sorted by the old rules, once.
            runCatching {
                if (settings.current().classVersion < CLASS_RULES) {
                    conversations.reclassify()
                    settings.setClassVersion(CLASS_RULES)
                }
            }.onFailure { CrashLog.warn("reclassify", it) }
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

    /** Long-press on Barq's icon: "Unread" opens the inbox on unread conversations only. */
    private fun publishShortcuts() = runCatching {
        val unread = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "unread")
            .setShortLabel(getString(R.string.shortcut_unread))
            .setLongLabel(getString(R.string.shortcut_unread_long))
            .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.drawable.ic_shortcut_unread))
            .setIntent(
                android.content.Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_UNREAD)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            .build()
        // Labels follow the phone's language, so the shortcut is refreshed at every start.
        androidx.core.content.pm.ShortcutManagerCompat.setDynamicShortcuts(this, listOf(unread))
    }.onFailure { CrashLog.warn("shortcuts", it) }

    private suspend fun seedDefaults() {
        if (templates.count() == 0) {
            resources.getStringArray(R.array.default_templates).toList()
                .forEachIndexed { i, t -> templates.upsert(TemplateEntity(text = t, position = i)) }
        }
        replyEngine.rebuildIfEmpty()
    }

    private companion object {
        /** Bump when the classification rules change, so old conversations are sorted again. */
        const val CLASS_RULES = 2
    }
}
