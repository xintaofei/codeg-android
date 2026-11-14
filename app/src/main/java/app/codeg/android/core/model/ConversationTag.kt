package app.codeg.android.core.model

import kotlinx.serialization.Serializable

/**
 * A conversation tag (Rust `ConversationTagDetail`). [folderId] is its scope: null for a global
 * tag, otherwise the ROOT folder whose conversations (worktrees included) it is offered on. A
 * tag's scope never changes. [color] is always a normalized lowercase `#rrggbb`.
 */
@Serializable
data class ConversationTag(
    val id: Int,
    val folderId: Int? = null,
    val name: String,
    val color: String,
    val sortOrder: Int = 0,
)

/**
 * The app-wide "branch tag" setting (Rust `ConversationBranchTag`): whether each conversation's
 * `git_branch` is drawn as a chip ahead of its tags, and in which colour. Not stored per
 * conversation.
 */
@Serializable
data class BranchTagSetting(
    val enabled: Boolean = false,
    val color: String = DEFAULT_COLOR,
) {
    companion object {
        /** The server's default (`DEFAULT_BRANCH_TAG_COLOR`): the gray preset. */
        const val DEFAULT_COLOR = "#6e7781"
    }
}
