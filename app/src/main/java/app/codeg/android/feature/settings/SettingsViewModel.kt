package app.codeg.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.cache.ConversationDiskCache
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** Backs the Settings tab: live appearance prefs + the About server/version info. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettingsStore: AppSettingsStore,
    private val repository: ServerRepository,
    private val diskCache: ConversationDiskCache,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        appSettingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _about = MutableStateFlow(AboutInfo())
    val about: StateFlow<AboutInfo> = _about.asStateFlow()

    private val _cache = MutableStateFlow(LocalCacheInfo())
    val cache: StateFlow<LocalCacheInfo> = _cache.asStateFlow()

    fun refreshCacheSize() = viewModelScope.launch {
        try {
            val bytes = diskCache.sizeBytes()
            _cache.update { it.copy(sizeBytes = bytes) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _cache.update { it.copy(error = error.localizedMessage ?: error.toString()) }
        }
    }

    fun clearCache() {
        if (_cache.value.clearing) return
        _cache.update { it.copy(clearing = true, cleared = false, error = null) }
        viewModelScope.launch {
            try {
                diskCache.clearAll()
                val bytes = diskCache.sizeBytes()
                _cache.update { it.copy(clearing = false, cleared = true, sizeBytes = bytes) }
            } catch (cancelled: CancellationException) {
                _cache.update { it.copy(clearing = false) }
                throw cancelled
            } catch (error: Exception) {
                _cache.update { it.copy(clearing = false, error = error.localizedMessage ?: error.toString()) }
            }
        }
    }

    init {
        viewModelScope.launch {
            val profile = repository.selectedProfile.first()
            _about.update { it.copy(serverName = profile?.name) }
            if (profile != null) {
                val client = repository.client(profile)
                val version = client?.let { runCatching { it.health().version }.getOrNull() }
                _about.update { it.copy(serverVersion = version) }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { appSettingsStore.setThemeMode(mode) }
    fun setAccent(accentId: String) = viewModelScope.launch { appSettingsStore.setAccent(accentId) }
}

data class AboutInfo(
    val serverName: String? = null,
    val serverVersion: String? = null,
)

data class LocalCacheInfo(
    val sizeBytes: Long? = null,
    val clearing: Boolean = false,
    val cleared: Boolean = false,
    val error: String? = null,
)
