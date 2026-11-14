package app.codeg.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * One JetBrains AIR typed session failure (Rust `SessionFailureRecord`), published by the
 * adapters codeg advertises AIR to (claude-agent-acp, codex-acp). The wire carries UPSERTS only:
 * adapters never publish a resolution, so [resolved] is inferred by the client at turn and
 * prompt boundaries (see `SessionFailures`).
 */
@Serializable
data class SessionFailureRecord(
    val id: String,
    val revision: Long,
    /** `connection|access|limit|request|service|unknown` today; kept a string so a newer
     *  vocabulary degrades to the fallback label. */
    val category: String = "unknown",
    /** `warning` (transient, auto-recovering) or `error` (terminal). */
    val severity: String = "error",
    /** Adapter-authored user-facing text, shown verbatim. May be empty: the category's label
     *  stands in. */
    val title: String = "",
    val details: String? = null,
    /** Suggested recovery actions, a subset of `retry|login|new_session` today. */
    val actions: List<String> = emptyList(),
    /** Client-inferred lifecycle; a snapshot may carry one the client missed the events of. */
    val resolved: Boolean = false,
    /** Closed by the user rather than settled — a silenced incident isn't a recovered one.
     *  Client-local, never on the wire. */
    @Transient val dismissed: Boolean = false,
)

/**
 * One plugin Claude Code could not load (Rust `PluginLoadFailure`, the CLI's `plugin_errors`
 * entry): [plugin] is `name@marketplace`, or a positional `inline[N]` / `synced[N]` tag, which
 * [path] then names better. [kind] is the CLI's open category; [message] its own English.
 */
@Serializable
data class PluginLoadFailure(
    val plugin: String,
    val kind: String = "",
    val message: String = "",
    val path: String? = null,
)
