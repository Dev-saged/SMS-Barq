package dev.saged.barq

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.saged.barq.data.db.AutoReplyDao
import dev.saged.barq.data.db.TemplateDao
import dev.saged.barq.data.db.TemplateEntity
import dev.saged.barq.di.AppScope
import dev.saged.barq.domain.AutoReplyEngine
import dev.saged.barq.notifications.Notifier
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        maintenance.ensureScheduled()
        scope.launch { seedDefaults() }
    }

    private suspend fun seedDefaults() {
        if (templates.count() == 0) {
            listOf("تمام", "أنا بالطريق", "بتواصل معك لاحقاً", "شكراً لك", "مشغول حالياً")
                .forEachIndexed { i, t -> templates.upsert(TemplateEntity(text = t, position = i)) }
        }
        if (autoReply.ruleCount() == 0) {
            AutoReplyEngine.defaultRules.forEach { autoReply.upsertRule(it) }
        }
    }
}
