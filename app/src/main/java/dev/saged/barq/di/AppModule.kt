package dev.saged.barq.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.saged.barq.data.db.BarqDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): BarqDatabase =
        Room.databaseBuilder(context, BarqDatabase::class.java, "barq.db")
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*BarqDatabase.ALL_MIGRATIONS)
            .build()

    @Provides fun conversationDao(db: BarqDatabase) = db.conversations()
    @Provides fun messageDao(db: BarqDatabase) = db.messages()
    @Provides fun blockDao(db: BarqDatabase) = db.blocks()
    @Provides fun ruleDao(db: BarqDatabase) = db.rules()
    @Provides fun templateDao(db: BarqDatabase) = db.templates()
    @Provides fun autoReplyDao(db: BarqDatabase) = db.autoReply()
    @Provides fun replyDao(db: BarqDatabase) = db.replies()
    @Provides fun groupDao(db: BarqDatabase) = db.groups()
    @Provides fun trashDao(db: BarqDatabase) = db.trash()

    /** Long-lived scope for work that must outlive a screen (receivers, sync). */
    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
