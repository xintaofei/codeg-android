package app.codeg.android.core.network

import dagger.Lazy
import app.codeg.android.core.cache.ConversationDiskCache
import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds per-server [CodegClient] / [EventStream] instances over the shared
 * (singleton) Ktor [HttpClient]. The base URL + token are runtime values chosen
 * by the user when a server is selected, so the clients are cheap value objects
 * created on demand — mirroring the iOS value-type `CodegClient`.
 *
 * The [HttpClient] is injected as [Lazy] so the OkHttp engine (and the Ktor /
 * OkHttp class graph) is only constructed on the first actual network call —
 * which always happens inside a coroutine, off the main thread. This keeps
 * engine construction off the cold-start first-frame critical path: this factory
 * sits in the singleton graph reached when the first ViewModel is created during
 * composition, and building the engine there would otherwise stall the first
 * frame (and, under heavy CPU contention, risk a startup ANR).
 */
@Singleton
class CodegClientFactory @Inject constructor(
    private val http: Lazy<HttpClient>,
    private val cache: ConversationDiskCache,
) {
    fun client(baseUrl: String, token: String): CodegClient =
        CodegClient(baseUrl, token, http.get())

    fun savedClient(profileId: String, baseUrl: String, token: String): CodegClient =
        CodegClient(baseUrl, token, http.get(), cache, cache.scope(profileId, baseUrl, token))

    fun eventStream(baseUrl: String, token: String): EventStream =
        EventStream(baseUrl, token, http.get())
}
