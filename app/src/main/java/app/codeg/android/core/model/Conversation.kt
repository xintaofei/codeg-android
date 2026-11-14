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
    /** `regular`, `chat`, `loop` or `delegate`; absent on an older server. */
    val kind: String? = null,
    /** The delegating conversation, on a delegation child (a sub-session). */
    val parentId: Int? = null,
    /** The tags on this conversation, ascending; absent on the wire when none. May name a tag
     *  since deleted — an id no tag list has is skipped. */
    val tagIds: List<Int> = emptyList(),
) {
    /** Non-empty, trimmed user title, or null for an unnamed session. Render
     * verbatim (user data); fall back to a localized "Untitled session" only
     * when this is null. */
    val trimmedTitle: String?
        get() = title?.takeIf { it.isNotBlank() }

    val isPinned: Boolean get() = pinnedAt != null

    /** Whether it may move to another folder (`move_conversations`): a root conversation, regular
     *  or chat. A sub-session moves with its parent. */
    val isMovable: Boolean get() = parentId == null && (kind == null || kind == "regular" || kind == "chat")
}

/** What `move_conversations` did (camelCase on the wire). */
@Serializable
data class MoveConversationsResult(
    /** Root conversations moved; their delegation children went with them. */
    val moved: List<Int> = emptyList(),
    val skipped: List<MoveConversationSkip> = emptyList(),
)

@Serializable
data class MoveConversationSkip(
    val conversationId: Int,
    /** `not_found`, `not_movable`, `already_there`, `task_owned`, `busy`, `conflict`,
     *  `target_unavailable`. */
    val reason: String,
)

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
 *
 * When the request asked for a window ([TurnWindow]), [turns] holds only the
 * full transcript from [turnsOffset] onward, and the five window fields describe
 * it. Every OTHER field — [summary] (incl. its `messageCount`), [sessionStats],
 * [inFlightUserTurnId] — still describes the FULL transcript.
 */
@Serializable
data class ConversationDetail(
    val summary: ConversationSummary,
    val turns: List<MessageTurn> = emptyList(),
    val sessionStats: SessionStats? = null,
    val inFlightUserTurnId: String? = null,
    /** Global index of `turns[0]`; absent on a legacy (pre-0.24.0) full response. */
    val turnsOffset: Int? = null,
    /** Full-transcript turn count. */
    val turnsTotal: Int? = null,
    /** Assistant turns in `full[0..turnsOffset)` — converts a history baseline
     * between global and window coordinates. */
    val assistantTurnsBeforeOffset: Int? = null,
    /** User turns in `full[0..turnsOffset)`. Absent from a server without the outline
     * route — which is how a client tells it isn't there. */
    val userTurnsBeforeOffset: Int? = null,
    /** Structural fingerprint of `full[0..turnsOffset)` as fixed-width 16-hex. */
    val prefixHash: String? = null,
    /** Max timestamp across `full[0..turnsOffset)`; absent when the window covers
     * everything. */
    @Serializable(with = InstantSerializer::class)
    val uncoveredPrefixMaxTs: Instant? = null,
    /** Byte offset of the agent transcript this read was parsed through. An out-of-turn overlay
     *  turn read at or before it is in [turns] now (`background_activity`'s watermark). */
    val transcriptWatermark: Long? = null,
) {
    /**
     * The window this response describes, or null when the server answered with
     * a legacy full transcript. The server sets the fields together, so one
     * check gates every windowed behaviour: paging, seam verification, and the
     * `turnsTotal`-based progress comparisons all disable themselves against an
     * older server and the client keeps its pre-0.24.0 behaviour.
     */
    val window: TurnWindowState?
        get() {
            val offset = turnsOffset ?: return null
            val total = turnsTotal ?: return null
            val hash = prefixHash ?: return null
            return TurnWindowState(offset = offset, total = total, prefixHash = hash)
        }

    /**
     * Full-transcript turn count, falling back to the loaded length for a legacy
     * response. Read through [window] rather than off [turnsTotal] directly, so a
     * half-populated payload — which [window] already refuses to trust — cannot
     * feed a total into the reconcile comparisons by the side door.
     */
    val effectiveTotal: Int get() = window?.total ?: turns.size
}

/**
 * One page of older history (Rust `ConversationTurnsPage`), covering
 * `full[turnsOffset until turnsOffset + turns.size)` and ending just before the
 * requested `beforeIndex`. A light response — no summary, no stats, no live
 * correlation.
 */
@Serializable
data class ConversationTurnsPage(
    val turns: List<MessageTurn> = emptyList(),
    val turnsOffset: Int,
    val turnsTotal: Int,
    val assistantTurnsBeforeOffset: Int = 0,
    /** Fingerprint of `full[0..turnsOffset)` — the client's new window
     * fingerprint after a successful prepend. */
    val prefixHash: String,
    /**
     * Fingerprint of `full[0..min(beforeIndex, total))` — the seam proof. It
     * MUST equal the window fingerprint the client held when it asked, or the
     * prefix was rewritten between the two requests and this page cannot legally
     * join what is already loaded.
     */
    val prefixHashBeforeIndex: String,
    @Serializable(with = InstantSerializer::class)
    val uncoveredPrefixMaxTs: Instant? = null,
)

/**
 * Where the loaded transcript sits inside the full one. Held by the session so
 * a refresh can ask for the same coordinates ([TurnWindow.FromIndex]) and a page
 * request can prove its seam.
 */
data class TurnWindowState(
    /** Global index of the first loaded turn. */
    val offset: Int,
    /** Full-transcript turn count. */
    val total: Int,
    /** Fingerprint of the turns BEFORE [offset] — what a page must match. */
    val prefixHash: String,
) {
    /** Whether older history exists above the loaded window. */
    val hasOlder: Boolean get() = offset > 0
}

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

/**
 * What `acp_fork` did (camelCase on the wire). This conversation and its connection went on
 * to [forkedSessionId]; a new row, [siblingConversationId], keeps the history as it was.
 */
@Serializable
data class ForkResult(
    val forkedSessionId: String = "",
    val originalSessionId: String = "",
    val siblingConversationId: Int? = null,
)

/**
 * Every turn above the loaded window, cut down to what the message navigator needs (Rust
 * `ConversationOutline`): `turns[i]` is global turn `i`; a user turn keeps its first text, an
 * assistant turn only its tool calls. [prefixHash] fingerprints all of [turns], to prove they are
 * the window's prefix.
 */
@Serializable
data class ConversationOutline(
    val turns: List<MessageTurn> = emptyList(),
    val turnsTotal: Int = 0,
    val prefixHash: String = "",
)
