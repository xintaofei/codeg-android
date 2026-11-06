package app.codeg.android.core.datastore

import kotlinx.serialization.Serializable

/**
 * A saved codeg server connection. The auth token is NOT stored here — it lives
 * in the [app.codeg.android.core.security.SecretStore], keyed by [id]. Mirrors
 * the iOS `ServerProfile` (Keychain-backed token).
 */
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val mesh: MeshSettings? = null,
    val connectionRevision: String = "",
)

/** Public connection settings only; the mesh password lives in SecretStore. */
@Serializable
data class MeshSettings(
    val networkName: String,
    val peers: List<String>,
    val ipv4: String = "",
)
