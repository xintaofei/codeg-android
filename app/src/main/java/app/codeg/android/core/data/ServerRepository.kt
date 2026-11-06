package app.codeg.android.core.data

import app.codeg.android.core.common.DispatcherProvider
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.ServerStore
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.mesh.MeshTransport
import app.codeg.android.core.mesh.MeshConfig
import app.codeg.android.core.model.HealthResponse
import app.codeg.android.core.network.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import app.codeg.android.core.network.CodegClientFactory
import app.codeg.android.core.network.EventStream
import app.codeg.android.core.security.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point the UI layer uses for everything server-scoped: the saved
 * profiles + selection (from [ServerStore]), their tokens (from [SecretStore]),
 * and building API clients for them (via [CodegClientFactory]). Keeps the
 * token-handling and client construction in one place so ViewModels stay thin.
 */
@Singleton
class ServerRepository @Inject constructor(
    private val serverStore: ServerStore,
    private val secretStore: SecretStore,
    private val factory: CodegClientFactory,
    private val dispatchers: DispatcherProvider,
    private val meshTransport: MeshTransport = MeshTransport(),
) {
    val profiles: Flow<List<ServerProfile>> = serverStore.profiles
    val selectedId: Flow<String?> = serverStore.selectedId

    /** Broadcast that a conversation was renamed/pinned/status-changed/deleted, so
     *  list-type screens can refetch. The faithful equivalent of iOS's
     *  `NotificationCenter` `conversationsDidChange`. */
    private val _conversationsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val conversationsChanged: SharedFlow<Unit> = _conversationsChanged.asSharedFlow()

    fun notifyConversationsChanged() { _conversationsChanged.tryEmit(Unit) }

    /** Broadcast that the folder set changed (e.g. a worktree was registered/opened
     *  during a branch switch), so project lists can refetch. iOS's `foldersDidChange`. */
    private val _foldersChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val foldersChanged: SharedFlow<Unit> = _foldersChanged.asSharedFlow()

    fun notifyFoldersChanged() { _foldersChanged.tryEmit(Unit) }

    val selectedProfile: Flow<ServerProfile?> =
        combine(profiles, selectedId) { list, id -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    /** The token for a profile, read off the IO dispatcher (Keystore decrypt). */
    suspend fun token(id: String): String? =
        withContext(dispatchers.io) { secretStore.get(id) }

    /** Save (insert/replace) a profile and its token. */
    suspend fun save(profile: ServerProfile, token: String, meshPassword: String = "") {
        if (profile.mesh != null) MeshConfig.validate(profile, meshPassword)
        withContext(dispatchers.io) {
            secretStore.put(profile.id, token)
            if (profile.mesh == null) secretStore.remove(meshSecretKey(profile.id))
            else secretStore.put(meshSecretKey(profile.id), meshPassword)
        }
        // Token/password-only edits must invalidate cached clients and restart the selected transport.
        serverStore.upsert(profile.copy(connectionRevision = UUID.randomUUID().toString()))
    }

    suspend fun delete(id: String) {
        serverStore.delete(id)
        withContext(dispatchers.io) { secretStore.remove(id); secretStore.remove(meshSecretKey(id)) }
    }

    suspend fun select(id: String?) = serverStore.select(id)

    /** An API client for a saved profile, or null if its token is missing. */
    suspend fun client(profile: ServerProfile): CodegClient? {
        val token = token(profile.id) ?: return null
        return meshTransport.address(profile)?.let { factory.client(it, token) }
    }

    /** An event stream for a saved profile, or null if its token is missing. */
    suspend fun eventStream(profile: ServerProfile): EventStream? {
        val token = token(profile.id) ?: return null
        return meshTransport.address(profile)?.let { factory.eventStream(it, token) }
    }

    suspend fun meshPassword(id: String): String =
        withContext(dispatchers.io) { secretStore.get(meshSecretKey(id)).orEmpty() }

    suspend fun prepare(profile: ServerProfile?) {
        if (profile == null) { meshTransport.disconnect(); return }
        val address = meshTransport.prepare(profile, if (profile.mesh != null) meshPassword(profile.id) else "")
        if (profile.mesh != null) {
            val savedToken = token(profile.id) ?: error("The saved server token is missing. Edit this server to sign in again.")
            awaitHealth(factory.client(address, savedToken))
        }
    }

    suspend fun disconnect() = meshTransport.disconnect()

    suspend fun testConnection(profile: ServerProfile, token: String, meshPassword: String): HealthResponse =
        meshTransport.test(profile, meshPassword) { address ->
            val client = factory.client(address, token)
            if (profile.mesh == null) client.health() else awaitHealth(client)
        }

    private suspend fun awaitHealth(client: CodegClient): HealthResponse = withTimeout(45_000) {
        while (true) {
            try {
                val health = client.health()
                requireHealthy(health)
                return@withTimeout health
            }
            catch (error: CancellationException) { throw error }
            catch (error: ApiError) {
                // Authentication/protocol errors are final; only network establishment retries.
                if (error !is ApiError.Transport) throw error
            }
            delay(500)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun meshSecretKey(id: String) = "mesh-password:$id"

    /** A transient client for an unsaved server (e.g. "Test Connection"). */
    fun transientClient(baseUrl: String, token: String): CodegClient =
        factory.client(baseUrl, token)
}

internal fun requireHealthy(health: HealthResponse) {
    check(health.status == "ok" && health.version.isNotBlank()) { "The server did not return a healthy Codeg response." }
}
