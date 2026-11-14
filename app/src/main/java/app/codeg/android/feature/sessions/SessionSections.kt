package app.codeg.android.feature.sessions

import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.ConversationTag
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.feature.tags.ConversationTags
import app.codeg.android.feature.tags.TagFilter

/**
 * One rendered group in the Chats list, fully sorted and filtered.
 *
 * Built off the main thread by [buildSessionSections] (exposed as
 * [SessionListViewModel.sections]) so the O(n·log n) grouping/sorting never runs
 * during composition — the `LazyColumn` just iterates these pre-sorted rows, which
 * is what keeps a very large session list scrolling smoothly. Mirrors the iOS
 * `SessionListView` sectioning (Pinned / per-folder / Other), realized on Android
 * as native collapsible sections rather than zoom-drill cards.
 */
data class SessionSection(
    /** Stable id used for the collapse set and the header's LazyColumn key. */
    val id: String,
    val kind: SectionKind,
    val rows: List<SessionRowItem>,
) {
    val count: Int get() = rows.size
}

/** What a [SessionSection] represents — drives its header icon, label, and tint. */
sealed interface SectionKind {
    /** Pinned conversations across all folders. */
    data object Pinned : SectionKind

    /** One folder's non-pinned conversations. [colorHex] is the folder's server color. */
    data class Folder(val name: String, val colorHex: String) : SectionKind

    /** Non-pinned conversations whose folder isn't in the folder list (orphans). */
    data object Other : SectionKind
}

/** One conversation row, with its cross-folder tag pre-resolved. */
data class SessionRowItem(
    val conversation: ConversationSummary,
    /** Dim folder tag shown on cross-folder rows (Pinned/Other); `null` inside a
     * folder group, where the folder is already the header. */
    val folderName: String?,
    /** Its tags this client knows, in display order (resolved off the main thread too). */
    val tags: List<ConversationTag> = emptyList(),
    /** Its branch, when the branch tag is on and it has one. */
    val branchChip: String? = null,
)

/**
 * Compose [SessionGrouping]'s pure accessors into the ordered section list shown by
 * the Chats screen: Pinned (if any) → one section per folder in
 * [SessionGrouping.sortedFolders] order (empty folders kept, matching iOS so their
 * header still shows) → Other (if any).
 *
 * `folderName` is resolved here, once, for the cross-folder sections only, so each
 * row needs no map lookup at compose time. Pure and allocation-light; the view model
 * runs it on `Dispatchers.Default`.
 */
fun buildSessionSections(
    folders: List<FolderDetail>,
    conversations: List<ConversationSummary>,
    tagsById: Map<Int, ConversationTag> = emptyMap(),
    branchTagEnabled: Boolean = false,
    /** Narrows every section; folders it leaves empty go too (web `matchesTagFilter`). */
    filter: TagFilter = TagFilter.NONE,
): List<SessionSection> {
    val folderNames = SessionGrouping.folderNames(folders)
    val out = ArrayList<SessionSection>()
    val shown = if (filter.isActive) conversations.filter(filter::matches) else conversations
    fun row(c: ConversationSummary, folderName: String?) =
        SessionRowItem(c, folderName, ConversationTags.resolve(c.tagIds, tagsById), ConversationTags.branchChip(c, branchTagEnabled))
    buildSections(folders, shown, folderNames, out, ::row)
    return if (filter.isActive) out.filter { it.count > 0 } else out
}

private fun buildSections(
    folders: List<FolderDetail>,
    conversations: List<ConversationSummary>,
    folderNames: Map<Int, String>,
    out: ArrayList<SessionSection>,
    row: (ConversationSummary, String?) -> SessionRowItem,
) {

    val pinned = SessionGrouping.pinned(conversations)
    if (pinned.isNotEmpty()) {
        out += SessionSection(
            id = "pinned",
            kind = SectionKind.Pinned,
            rows = pinned.map { row(it, folderNames[it.folderId]) },
        )
    }

    for (group in SessionGrouping.folderGroups(folders, conversations)) {
        out += SessionSection(
            id = "folder-${group.folder.id}",
            kind = SectionKind.Folder(group.folder.name, group.folder.color),
            rows = group.conversations.map { row(it, null) },
        )
    }

    val other = SessionGrouping.ungrouped(folders, conversations)
    if (other.isNotEmpty()) {
        out += SessionSection(
            id = "other",
            kind = SectionKind.Other,
            rows = other.map { row(it, folderNames[it.folderId]) },
        )
    }
}
