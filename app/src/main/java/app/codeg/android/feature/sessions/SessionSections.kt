package app.codeg.android.feature.sessions

import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderVisibility
import app.codeg.android.core.datastore.SessionListPreferences
import app.codeg.android.core.datastore.SessionSortMode
import java.time.Instant

internal const val SESSION_PAGE_SIZE = 20
internal const val RECENT_PAGE_SIZE = 10

/**
 * One rendered group in the Chats list, fully sorted and filtered.
 *
 * Built off the main thread by [buildSessionSections] (exposed as
 * [SessionListViewModel.sections]) so the O(n·log n) grouping/sorting never runs
 * during composition — the `LazyColumn` just iterates these pre-sorted rows, which
 * is what keeps a very large session list scrolling smoothly. Uses the web
 * client's Pinned / Chats / project split with native collapsible sections.
 */
data class SessionSection(
    /** Stable id used for the collapse set and the header's LazyColumn key. */
    val id: String,
    val kind: SectionKind,
    val rows: List<SessionRowItem>,
) {
    val count: Int get() = rows.size
    val pageSize: Int get() = if (kind == SectionKind.Recent) RECENT_PAGE_SIZE else SESSION_PAGE_SIZE
}

/** What a [SessionSection] represents — drives its header icon, label, and tint. */
sealed interface SectionKind {
    /** Pinned conversations across all folders. */
    data object Pinned : SectionKind

    /** All folderless chats, without exposing their per-conversation scratch folders. */
    data object Chats : SectionKind

    data object Recent : SectionKind

    /** One folder's non-pinned conversations. [colorHex] is the folder's server color. */
    data class Folder(val name: String, val colorHex: String, val folderId: Int? = null) : SectionKind

    /** Non-pinned conversations whose folder isn't in the folder list (orphans). */
    data object Other : SectionKind
}

/** One conversation row, with its cross-folder tag pre-resolved. */
data class SessionRowItem(
    val conversation: ConversationSummary,
    /** Dim folder tag shown on cross-folder rows (Pinned/Other/Recent); `null` inside a
     * folder group, where the folder is already the header. */
    val folderName: String?,
    val timestamp: Instant = conversation.updatedAt,
)

/**
 * Compose [SessionGrouping]'s pure accessors into the ordered section list shown by
 * the Chats screen: Pinned → Chats → non-empty projects → Other → Recent.
 * Device-hidden folders are omitted outside Pinned; row limits apply only when rendering.
 *
 * `folderName` is resolved here, once, for the cross-folder sections only, so each
 * row needs no map lookup at compose time. Pure and allocation-light; the view model
 * runs it on `Dispatchers.Default`.
 */
fun buildSessionSections(
    folders: List<FolderDetail>,
    conversations: List<ConversationSummary>,
    preferences: SessionListPreferences = SessionListPreferences(),
    openFolderIds: Set<Int> = folders.map { it.id }.toSet(),
    hiddenFolderIds: Set<Int> = emptySet(),
    folderOrder: List<Int> = emptyList(),
): List<SessionSection> {
    val folderNames = SessionGrouping.folderNames(folders)
    val hidden = FolderVisibility.hiddenIds(folders, hiddenFolderIds)
    val visibleConversations = if (hidden.isEmpty()) conversations else conversations.filter { it.folderId !in hidden }
    val out = ArrayList<SessionSection>()
    fun row(conversation: ConversationSummary, crossFolder: Boolean = false, updated: Boolean = false) =
        SessionRowItem(
            conversation,
            if (crossFolder) folderNames[conversation.folderId] else null,
            if (updated || preferences.sortMode == SessionSortMode.UPDATED) conversation.updatedAt else conversation.createdAt,
        )

    val pinned = SessionGrouping.pinned(conversations)
    if (pinned.isNotEmpty()) {
        out += SessionSection(
            id = "pinned",
            kind = SectionKind.Pinned,
            rows = pinned.map { row(it, crossFolder = true) },
        )
    }

    val chats = SessionGrouping.chats(folders, visibleConversations)
    if (chats.isNotEmpty()) {
        out += SessionSection(
            id = "chats",
            kind = SectionKind.Chats,
            rows = chats.map { row(it, updated = true) },
        )
    }

    for (group in SessionGrouping.folderGroups(folders, visibleConversations, sortMode = preferences.sortMode, folderOrder = folderOrder)) {
        out += SessionSection(
            id = "folder-${group.folder.id}",
            kind = SectionKind.Folder(group.folder.name, group.folder.color, group.folder.id),
            rows = group.conversations.map { row(it) },
        )
    }

    val other = SessionGrouping.ungrouped(folders, visibleConversations, sortMode = preferences.sortMode)
    if (other.isNotEmpty()) {
        out += SessionSection(
            id = "other",
            kind = SectionKind.Other,
            rows = other.map { row(it, crossFolder = true) },
        )
    }

    if (preferences.showRecent) {
        out += SessionSection(
            id = "recent",
            kind = SectionKind.Recent,
            rows = SessionGrouping.recent(folders, visibleConversations, openFolderIds, preferences)
                .map { row(it, crossFolder = true, updated = true) },
        )
    }

    return out
}
