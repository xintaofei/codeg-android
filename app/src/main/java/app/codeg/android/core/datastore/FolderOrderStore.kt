package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderOrdering
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Uses the same profile-and-endpoint scope as local folder visibility. */
@Singleton
class FolderOrderStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    fun order(scope: String) = dataStore.data.map { read(it, scope) }.distinctUntilChanged()

    suspend fun move(scope: String, folders: List<FolderDetail>, hiddenIds: Set<Int>, folderId: Int, offset: Int) {
        dataStore.edit { prefs ->
            val next = FolderOrdering.move(folders, read(prefs, scope), hiddenIds, folderId, offset)
            prefs[key(scope)] = next.joinToString(",")
        }
    }

    suspend fun reset(scope: String) {
        dataStore.edit { it.remove(key(scope)) }
    }

    suspend fun reorder(scope: String, folders: List<FolderDetail>, hiddenIds: Set<Int>, visibleOrder: List<Int>) {
        dataStore.edit { prefs ->
            val next = FolderOrdering.reorder(folders, read(prefs, scope), hiddenIds, visibleOrder)
            prefs[key(scope)] = next.joinToString(",")
        }
    }

    private fun read(prefs: Preferences, scope: String): List<Int> =
        prefs[key(scope)].orEmpty().split(',').mapNotNull { it.toIntOrNull() }.distinct()

    private fun key(scope: String) = stringPreferencesKey("folder_order_$scope")
}
