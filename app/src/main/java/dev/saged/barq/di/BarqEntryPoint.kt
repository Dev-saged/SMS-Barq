package dev.saged.barq.di

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.saged.barq.data.ConversationRepository
import dev.saged.barq.data.IncomingRepository
import dev.saged.barq.data.OutgoingRepository
import dev.saged.barq.data.contacts.ContactsRepository
import dev.saged.barq.data.db.MessageDao
import dev.saged.barq.data.settings.SettingsRepository
import dev.saged.barq.notifications.Notifier
import dev.saged.barq.sms.IncomingProcessor
import dev.saged.barq.sms.ScheduleManager
import dev.saged.barq.sms.SendEngine
import dev.saged.barq.sms.SendQueue
import dev.saged.barq.work.MaintenanceScheduler

/**
 * Dependency access for BroadcastReceivers and widgets. Using an entry point (instead of field
 * injection) is the most reliable way: receivers are created by the system on every broadcast.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BarqEntryPoint {
    fun incomingProcessor(): IncomingProcessor
    fun incoming(): IncomingRepository
    fun conversations(): ConversationRepository
    fun outgoing(): OutgoingRepository
    fun engine(): SendEngine
    fun contacts(): ContactsRepository
    fun notifier(): Notifier
    fun sendQueue(): SendQueue
    fun scheduler(): ScheduleManager
    fun messageDao(): MessageDao
    fun settings(): SettingsRepository
    fun maintenance(): MaintenanceScheduler
    fun updates(): dev.saged.barq.data.update.UpdateManager
}

val Context.barq: BarqEntryPoint
    get() = EntryPointAccessors.fromApplication(applicationContext, BarqEntryPoint::class.java)
