package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Device-local visibility, isolated by saved profile and endpoint. */
@Singleton
class HiddenFoldersStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    fun scope(profile: ServerProfile): String = MessageDigest.getInstance("SHA-256")
        .digest("${profile.id}\u0000${profile.baseUrl.trim().trimEnd('/')}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun hiddenIds(scope: String) = dataStore.data
        .map { prefs -> prefs[key(scope)].orEmpty().mapNotNull { it.toIntOrNull() }.toSet() }
        .distinctUntilChanged()

    suspend fun setHidden(scope: String, folderId: Int, hidden: Boolean) {
        dataStore.edit { prefs ->
            val ids = prefs[key(scope)].orEmpty()
            prefs[key(scope)] = if (hidden) ids + folderId.toString() else ids - folderId.toString()
        }
    }

    private fun key(scope: String) = stringSetPreferencesKey("hidden_folders_$scope")
}
