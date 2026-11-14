package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.codeg.android.feature.tags.TagFilter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** The Chats list's tag filter, kept per server profile — tag ids are a server's own. */
@Singleton
class TagFilterStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun filter(profileId: String): TagFilter =
        dataStore.data.first()[key(profileId)]
            ?.let { runCatching { json.decodeFromString(TagFilter.serializer(), it) }.getOrNull() }
            ?: TagFilter.NONE

    suspend fun save(profileId: String, filter: TagFilter) {
        dataStore.edit { prefs ->
            if (filter.isActive) prefs[key(profileId)] = json.encodeToString(TagFilter.serializer(), filter)
            else prefs.remove(key(profileId))
        }
    }

    private fun key(profileId: String) = stringPreferencesKey("tag_filter:$profileId")
}
