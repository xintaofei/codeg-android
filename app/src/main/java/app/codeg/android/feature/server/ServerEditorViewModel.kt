package app.codeg.android.feature.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.MeshSettings
import app.codeg.android.core.mesh.MeshConfig
import app.codeg.android.core.mesh.MeshTestConflict
import app.codeg.android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Add/edit form state for a server profile, plus the Test Connection probe. */
@HiltViewModel
class ServerEditorViewModel @Inject constructor(
    private val repository: ServerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val editingId: String? = savedStateHandle["serverId"]
    val isEditing: Boolean = editingId != null

    var name by mutableStateOf("")
        private set
    var url by mutableStateOf("")
        private set
    var token by mutableStateOf("")
        private set
    var useMesh by mutableStateOf(false)
        private set
    var networkName by mutableStateOf("")
        private set
    var networkPassword by mutableStateOf("")
        private set
    var peers by mutableStateOf("")
        private set
    var ipv4 by mutableStateOf("")
        private set
    private var testJob: Job? = null
    var test by mutableStateOf<TestState>(TestState.Idle)
        private set

    init {
        if (editingId != null) loadExisting(editingId)
    }

    fun onNameChange(value: String) { name = value }
    fun onUrlChange(value: String) { url = value; resetTest() }
    fun onTokenChange(value: String) { token = value; resetTest() }

    fun onUseMeshChange(value: Boolean) { useMesh = value; resetTest() }
    fun onNetworkNameChange(value: String) { networkName = value; resetTest() }
    fun onNetworkPasswordChange(value: String) { networkPassword = value; resetTest() }
    fun onPeersChange(value: String) { peers = value; resetTest() }
    fun onIpv4Change(value: String) { ipv4 = value; resetTest() }
    private fun resetTest() { testJob?.cancel(); test = TestState.Idle }

    private fun profile(id: String) = ServerProfile(
        id = id, name = name.trim(), baseUrl = CodegClient.normalizeBaseUrl(url),
        mesh = if (useMesh) MeshSettings(networkName.trim(), peers.split(',', '\n', '\r').map(String::trim).filter(String::isNotEmpty), ipv4.trim()) else null,
    )

    val canSave: Boolean
        get() = name.isNotBlank() && canTest

    val canTest: Boolean
        get() = url.isNotBlank() && token.isNotBlank() &&
            (!useMesh || (BuildConfig.EASYTIER_ENABLED && runCatching { MeshConfig.validate(profile(editingId ?: "test"), networkPassword) }.isSuccess))

    private fun loadExisting(id: String) {
        viewModelScope.launch {
            val profile = repository.profiles.first().firstOrNull { it.id == id } ?: return@launch
            name = profile.name
            url = profile.baseUrl
            token = repository.token(id).orEmpty()
            useMesh = profile.mesh != null
            networkName = profile.mesh?.networkName.orEmpty()
            peers = profile.mesh?.peers?.joinToString("\n").orEmpty()
            ipv4 = profile.mesh?.ipv4.orEmpty()
            networkPassword = repository.meshPassword(id)
        }
    }

    fun testConnection() {
        testJob?.cancel()
        val candidate = profile(UUID.randomUUID().toString())
        val savedToken = token.trim()
        val password = networkPassword
        testJob = viewModelScope.launch {
            test = TestState.Testing
            test = try {
                val health = repository.testConnection(candidate, savedToken, password)
                TestState.Success(health.version)
            } catch (error: MeshTestConflict) {
                TestState.Failure(requireNotNull(error.message))
            } catch (_: TimeoutCancellationException) {
                TestState.Failure("Connection timed out. Check the mesh settings and try again.")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                // Do not reflect native config or credential-bearing exception messages.
                TestState.Failure(if (candidate.mesh != null) "Check the mesh settings, peer reachability, and server token." else error.displayMessage())
            }
        }
    }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            val id = editingId ?: UUID.randomUUID().toString()
            val profile = profile(id)
            repository.save(profile, token.trim(), networkPassword)
            if (editingId == null) repository.select(id)
            onDone()
        }
    }
}

sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Success(val version: String) : TestState
    data class Failure(val message: String) : TestState
}
