package app.codeg.android.core.model

import app.codeg.android.core.model.wire.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A project/workspace on a codeg server (Rust `FolderDetail`). The app treats
 * folders as a filter dimension under a server and uses [path] as the
 * `workingDir` when (re)connecting an agent.
 *
 * Decoded with the snake_case response `Json`, so camelCase properties map to
 * `git_branch` / `default_agent_type` / `last_opened_at` / `sort_order` etc.
 */
@Serializable
data class FolderDetail(
    val id: Int,
    val name: String,
    val path: String,
    val gitBranch: String? = null,
    val defaultAgentType: AgentType? = null,
    @Serializable(with = InstantSerializer::class)
    val lastOpenedAt: Instant? = null,
    val sortOrder: Int = 0,
    val color: String = "",
    val parentId: Int? = null,
    val isChat: Boolean = false,
    val kind: String? = null,
) {
    /** New servers send kind; older servers send is_chat. Never infer this from the name. */
    val isChatFolder: Boolean get() = kind?.let { it == "chat" } ?: isChat
}
