package app.codeg.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.ServerProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-level state: appearance settings (drives the theme) and the saved servers
 * + selection. Owns server-selection / deletion actions.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    appSettingsStore: AppSettingsStore,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appSettingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** null = not loaded yet (show a splash); non-null = loaded (possibly empty). */
    val servers: StateFlow<List<ServerProfile>?> = serverRepository.profiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedProfile: StateFlow<ServerProfile?> = serverRepository.selectedProfile
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val connection = MutableStateFlow<ConnectionState>(ConnectionState.Loading)
    private val retry = MutableStateFlow(0)
    val managingServers = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            combine(serverRepository.selectedProfile, retry, managingServers) { profile, _, managing -> profile to managing }
                .collectLatest { (profile, managing) ->
                    if (managing) {
                        runCatching { serverRepository.disconnect() }
                        connection.value = ConnectionState.Loading
                        return@collectLatest
                    }
                    connection.value = ConnectionState.Connecting(profile)
                    try {
                        serverRepository.prepare(profile)
                        connection.value = ConnectionState.Ready(profile)
                    } catch (error: TimeoutCancellationException) {
                        runCatching { serverRepository.disconnect() }
                        connection.value = ConnectionState.Failed(profile)
                    } catch (error: CancellationException) {
                        runCatching { serverRepository.disconnect() }
                        throw error
                    } catch (_: Exception) {
                        runCatching { serverRepository.disconnect() }
                        connection.value = ConnectionState.Failed(profile)
                    }
                }
        }
    }

    fun retryConnection() { retry.update { it + 1 } }

    fun selectServer(id: String) {
        viewModelScope.launch { serverRepository.select(id); managingServers.value = false }
    }

    fun deleteServer(id: String) {
        viewModelScope.launch { serverRepository.delete(id) }
    }
}

sealed interface ConnectionState {
    data object Loading : ConnectionState
    data class Connecting(val profile: ServerProfile?) : ConnectionState
    data class Ready(val profile: ServerProfile?) : ConnectionState
    data class Failed(val profile: ServerProfile?) : ConnectionState
}
