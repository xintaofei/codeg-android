package app.codeg.android.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.codeg.android.core.common.DefaultDispatcherProvider
import app.codeg.android.core.common.DispatcherProvider
import app.codeg.android.core.cache.ConversationDiskCache
import java.io.File
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideConversationCache(@ApplicationContext context: Context): ConversationDiskCache =
        ConversationDiskCache(File(context.cacheDir, "conversation-snapshots"))

    /** Single Preferences DataStore backing both the server list and app settings. */
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("codeg_prefs")
        }

    @Provides
    @Singleton
    fun provideDispatchers(): DispatcherProvider = DefaultDispatcherProvider
}
