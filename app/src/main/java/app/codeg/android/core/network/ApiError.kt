package app.codeg.android.core.network

import kotlinx.serialization.Serializable

/**
 * Typed failure surface for the HTTP client, mirroring the iOS `APIError`.
 * Thrown by [CodegClient]; the UI layer maps these to user-facing messages.
 */
sealed class ApiError(message: String) : Exception(message) {
    /** A network/transport failure (no HTTP response, DNS, TLS, timeout). */
    data class Transport(val detail: String) : ApiError(detail)

    /** HTTP 401 — the token is missing or rejected. */
    data object Unauthorized : ApiError("Invalid or missing token")

    /** HTTP 409 / `turn_in_progress` — a turn is already running on the connection. */
    data object TurnInProgress : ApiError("A turn is already in progress")

    /** Any other non-2xx, with the server's `{code,message}` envelope if present. */
    data class Server(
        val status: Int,
        val code: String?,
        val serverMessage: String,
    ) : ApiError(serverMessage)

    /** The response body could not be decoded into the expected shape. */
    data class Decoding(val detail: String) : ApiError(detail)
}

/** Error envelope returned by the server on non-2xx (`{code, message, ...}`). */
@Serializable
data class ServerError(
    val code: String? = null,
    val message: String? = null,
)

/**
 * A reused/looked-up ACP connection the server no longer knows about — the caller
 * should drop the cached id and reconnect. Mirrors iOS `APIError.isStaleConnection`
 * (HTTP 404, or the `connection_not_found` / `unknown_connection` server codes).
 */
val ApiError.isStaleConnection: Boolean
    get() = this is ApiError.Server &&
        (status == 404 || code == "connection_not_found" || code == "unknown_connection")

/**
 * The server refused a mid-turn steering note because the turn had already finished
 * (`AcpError::NoActiveTurn`) — an expected race, not a fault: the agent can stop between
 * the user typing the note and tapping send.
 *
 * Matched on the message rather than [ApiError.Server.code] because the code cannot tell
 * this apart. The HTTP layer maps every recoverable feedback refusal — turn ended, no
 * channel on this session, empty/oversized text — onto one `AppErrorCode::InvalidInput`, so
 * they all arrive as 400 `invalid_input`; only the `AcpError` Display string survives. The
 * web client recognizes it the same way (`turn-busy.ts`), which is what keeps the marker
 * stable on the server side.
 */
val ApiError.isNoActiveTurn: Boolean
    get() = this is ApiError.Server && serverMessage.contains(NO_ACTIVE_TURN_MARKER, ignoreCase = true)

/**
 * The session has no channel left to carry a steering note (`AcpError::FeedbackDisabled`).
 *
 * Reachable mid-session, not just at launch: a native-only session (steering advertised, but
 * the `check_user_feedback` tool never injected) whose adapter mishandles the push gets its
 * native channel revoked server-side, and from then on has neither. The client has to stop
 * offering to send — the refusal is the only notice it gets.
 *
 * Matched on the message for the same reason as [isNoActiveTurn].
 */
val ApiError.isFeedbackDisabled: Boolean
    get() = this is ApiError.Server && serverMessage.contains(FEEDBACK_DISABLED_MARKER, ignoreCase = true)

/** Substring of `AcpError::NoActiveTurn`'s Display text ("no active turn to send feedback
 *  to"). A substring so the recognition survives the server elaborating the message. */
private const val NO_ACTIVE_TURN_MARKER = "no active turn"

/** Substring of `AcpError::FeedbackDisabled`'s Display text ("live feedback is disabled"). */
private const val FEEDBACK_DISABLED_MARKER = "live feedback is disabled"

/**
 * A **git remote** authentication failure (push/pull/fetch), signalling the
 * credential-retry flow to prompt for a token / username+password and retry.
 * Mirrors iOS `APIError.isAuthFailure`: the `authentication_failed` server code, or
 * a message matching git's known auth phrases. NOT [ApiError.Unauthorized] — that
 * is the codeg server token, never a git remote.
 */
val ApiError.isAuthFailure: Boolean
    get() {
        if (this !is ApiError.Server) return false
        if (code == "authentication_failed") return true
        val lower = serverMessage.lowercase()
        return lower.contains("authentication failed") ||
            lower.contains("could not read username") ||
            lower.contains("could not read password") ||
            lower.contains("logon failed")
    }
