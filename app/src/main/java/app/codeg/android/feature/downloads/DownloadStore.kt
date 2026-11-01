package app.codeg.android.feature.downloads

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable metadata only; no server auth token, issued ticket URL or downloaded file contents. */
@Singleton
internal class DownloadStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("file_download_history_v1")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(): List<DownloadRecord> = decode(dataStore.data.first()[key])

    suspend fun put(record: DownloadRecord) {
        dataStore.edit { prefs ->
            prefs[key] = json.encodeToString((listOf(record) + decode(prefs[key]).filterNot { it.key == record.key }).sortedByDescending { it.createdAt })
        }
    }

    suspend fun remove(id: String) {
        dataStore.edit { prefs -> prefs[key] = json.encodeToString(decode(prefs[key]).filterNot { it.key == id }) }
    }

    private fun decode(value: String?): List<DownloadRecord> = value?.let { json.decodeFromString<List<DownloadRecord>>(it) }.orEmpty()
}
