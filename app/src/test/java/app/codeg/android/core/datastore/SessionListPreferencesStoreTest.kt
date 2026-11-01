package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListPreferencesStoreTest {
    @Test
    fun `default and unknown stored values match web defaults`() = runTest {
        val dataStore = MemoryPreferences()
        assertEquals(SessionListPreferences(), SessionListPreferencesStore(dataStore).preferences.first())
        dataStore.data.value = preferencesOf(
            stringPreferencesKey("sessions_sort_mode") to "unknown",
            stringPreferencesKey("sessions_recent_filter") to "unknown",
        )
        assertEquals(SessionListPreferences(), SessionListPreferencesStore(dataStore).preferences.first())
    }

    @Test
    fun `choices survive store recreation and preserve appearance settings`() = runTest {
        val dataStore = MemoryPreferences()
        val appearance = AppSettingsStore(dataStore)
        appearance.setThemeMode(ThemeMode.DARK)
        val store = SessionListPreferencesStore(dataStore)
        store.setSortMode(SessionSortMode.UPDATED)
        store.setShowRecent(false)
        store.setRecentFilter(RecentSessionFilter.CLI)
        store.setRecentShowCompleted(true)
        assertEquals(
            SessionListPreferences(SessionSortMode.UPDATED, false, RecentSessionFilter.CLI, true),
            SessionListPreferencesStore(dataStore).preferences.first(),
        )
        assertEquals(ThemeMode.DARK, appearance.settings.first().themeMode)
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
