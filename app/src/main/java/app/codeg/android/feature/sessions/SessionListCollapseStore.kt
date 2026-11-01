package app.codeg.android.feature.sessions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Per-server layout survives a fresh app launch, including the layout to restore. */
@Singleton
class SessionListCollapseStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    internal fun state(serverId: String) = dataStore.data
        .map { it.layout(serverId) }
        .distinctUntilChanged()

    internal suspend fun update(serverId: String, transform: (SessionListCollapseState) -> SessionListCollapseState) {
        dataStore.edit { prefs ->
            val next = transform(prefs.layout(serverId))
            prefs[compactKey(serverId)] = next.compact
            prefs[collapsedKey(serverId)] = next.collapsed
            prefs[expandedKey(serverId)] = next.expandedInCompact
        }
    }

    private fun Preferences.layout(serverId: String) = SessionListCollapseState(
        compact = this[compactKey(serverId)] ?: false,
        collapsed = this[collapsedKey(serverId)] ?: emptySet(),
        expandedInCompact = this[expandedKey(serverId)] ?: emptySet(),
    )

    private fun compactKey(id: String) = booleanPreferencesKey("sessions_layout_${id}_compact")
    private fun collapsedKey(id: String) = stringSetPreferencesKey("sessions_layout_${id}_collapsed")
    private fun expandedKey(id: String) = stringSetPreferencesKey("sessions_layout_${id}_expanded_compact")
}

internal data class ServerSessionListLayout(val serverId: String, val state: SessionListCollapseState)
