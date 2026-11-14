package app.codeg.android.core.model

import kotlinx.serialization.Serializable

// The server calls behind the composer's inline `/` menu and `@` panel: the workspace file
// search (`search_workspace_files`), and the skills an agent's sessions load from disk
// (`acp_list_agent_runtime_skills`, which answers an [AgentSkillsListResult]).

/**
 * One workspace file search hit (Rust `WorkspaceFileEntry`): [path] is relative to the searched
 * root and always forward-slashed.
 */
@Serializable
data class WorkspaceFileEntry(
    val name: String = "",
    val path: String,
    /** `file` or `dir`. */
    val kind: String = "file",
) {
    val isDir: Boolean get() = kind == "dir"

    /** The entry's name, or its path's last segment when the server sent none. */
    val displayName: String get() = name.ifEmpty { path.substringAfterLast('/').ifEmpty { path } }
}

/**
 * Identifies one search to the backend (Rust `SearchRequest`, camelCase): the generation must
 * strictly increase per consumer — a stale one is answered with `[]` — and naming the current
 * one is how a search is cancelled.
 */
@Serializable
data class WorkspaceSearchRequest(val consumerId: String, val generation: Long)

@Serializable
data class WorkspaceFileSearchBody(
    val path: String,
    val query: String,
    val limit: Int,
    val request: WorkspaceSearchRequest,
)

@Serializable
data class CancelWorkspaceFileSearchBody(val request: WorkspaceSearchRequest)
