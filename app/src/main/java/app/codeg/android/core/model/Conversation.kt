package app.codeg.android.core.model

import app.codeg.android.core.model.wire.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A conversation/session persisted on a server (Rust `DbConversationSummary`).
 * Mutable-feeling optimistic fields on iOS (`title`, `status`, `pinnedAt`) are
 * `val`s here; the UI layer holds optimistic copies in its state.
 */
@Serializable
data class ConversationSummary(
    val id: Int,
    val folderId: Int,
    val title: String? = null,
    val agentType: AgentType,
    val status: ConversationStatus,
    val model: String? = null,
    val gitBranch: String? = null,
    val externalId: String? = null,
    val messageCount: Int = 0,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant,
    /** Server `pinned_at`, or null when not pinned. Pinning does NOT bump
     * `updated_at` — the "Pinned" group sorts by this instead. */
    @Serializable(with = InstantSerializer::class)
    val pinnedAt: Instant? = null,
    val kind: String? = null,
    val source: String? = null,
    val originCwd: String? = null,
) {
    /** Non-empty, trimmed user title, or null for an unnamed session. Render
     * verbatim (user data); fall back to a localized "Untitled session" only
     * when this is null. */
    val trimmedTitle: String?
        get() = title?.takeIf { it.isNotBlank() }

    val isPinned: Boolean get() = pinnedAt != null
}

/** Aggregate token/timing stats for a session (Rust `SessionStats`). */
@Serializable
data class SessionStats(
    val totalUsage: TurnUsage? = null,
    val totalTokens: Int? = null,
    val totalDurationMs: Int = 0,
    val contextWindowUsedTokens: Int? = null,
    val contextWindowMaxTokens: Int? = null,
    val contextWindowUsagePercent: Double? = null,
)

/**
 * Full session detail incl. message history (Rust `DbConversationDetail`).
 * Decode-only. [turns] uses the hand-written `ContentBlock` serializer for its
 * polymorphic blocks.
 */
@Serializable
data class ConversationDetail(
    val summary: ConversationSummary,
    val turns: List<MessageTurn> = emptyList(),
    val sessionStats: SessionStats? = null,
    val inFlightUserTurnId: String? = null,
    val turnsOffset: Int? = null,
    val turnsTotal: Int? = null,
    val prefixHash: String? = null,
)

@Serializable
data class ConversationTurnsPage(
    val turns: List<MessageTurn>,
    val turnsOffset: Int,
    val turnsTotal: Int,
    val prefixHash: String,
    val prefixHashBeforeIndex: String,
)

/** Returned by `acp_find_connection_for_conversation` when a live ACP
 * connection already owns a conversation. */
@Serializable
data class ConversationConnectionInfo(
    val connectionId: String,
    val eventSeq: Long = 0,
)

/** `health` endpoint response — used to validate a server profile. */
@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
)
