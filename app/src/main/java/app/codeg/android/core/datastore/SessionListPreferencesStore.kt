package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class SessionSortMode(val id: String) {
    CREATED("created"), UPDATED("updated");

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id } ?: CREATED
    }
}

enum class RecentSessionFilter(val id: String) {
    ALL("all"), CHATS("chats"), FOLDERS("folders"), CLI("cli");

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id } ?: ALL
    }
}

data class SessionListPreferences(
    val sortMode: SessionSortMode = SessionSortMode.CREATED,
    val showRecent: Boolean = true,
    val recentFilter: RecentSessionFilter = RecentSessionFilter.ALL,
    val recentShowCompleted: Boolean = false,
)

/** Device-local list preferences; never change the source data or global search. */
@Singleton
class SessionListPreferencesStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences = dataStore.data.map { prefs ->
        SessionListPreferences(
            sortMode = SessionSortMode.from(prefs[SORT]),
            showRecent = prefs[SHOW_RECENT] ?: true,
            recentFilter = RecentSessionFilter.from(prefs[RECENT_FILTER]),
            recentShowCompleted = prefs[RECENT_COMPLETED] ?: false,
        )
    }

    suspend fun setSortMode(mode: SessionSortMode) {
        dataStore.edit { it[SORT] = mode.id }
    }

    suspend fun setShowRecent(show: Boolean) {
        dataStore.edit { it[SHOW_RECENT] = show }
    }

    suspend fun setRecentFilter(filter: RecentSessionFilter) {
        dataStore.edit { it[RECENT_FILTER] = filter.id }
    }

    suspend fun setRecentShowCompleted(show: Boolean) {
        dataStore.edit { it[RECENT_COMPLETED] = show }
    }

    private companion object {
        val SORT = stringPreferencesKey("sessions_sort_mode")
        val SHOW_RECENT = booleanPreferencesKey("sessions_show_recent")
        val RECENT_FILTER = stringPreferencesKey("sessions_recent_filter")
        val RECENT_COMPLETED = booleanPreferencesKey("sessions_recent_show_completed")
    }
}
