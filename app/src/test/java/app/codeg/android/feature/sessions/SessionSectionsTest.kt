package app.codeg.android.feature.sessions

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.datastore.SessionListPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Locks the grouped section list the Chats screen renders: ordering (Pinned →
 * folders in sortOrder/name order → Other), per-section sort, membership
 * (pinned excluded from folder groups), and the pre-resolved cross-folder tag.
 */
class SessionSectionsTest {

    @Test
    fun `manual folder order changes project headers and keeps worktrees merged with all history`() {
        val folders = listOf(folder(1, "A"), folder(2, "B"), folder(3, "Branch").copy(parentId = 1))
        val conversations = listOf(conv(1, 1, 10), conv(2, 2, 20), conv(3, 3, 30))
        val sections = buildSessionSections(
            folders, conversations, SessionListPreferences(showRecent = false), folderOrder = listOf(2, 1, 3),
        )
        assertEquals(listOf("folder-2", "folder-1"), sections.map { it.id })
        assertEquals(listOf(3, 1), sections.last().rows.map { it.conversation.id })
        assertEquals(listOf(1, 2, 3), sections.flatMap { it.rows }.map { it.conversation.id }.sorted())
    }

    private fun buildWithoutRecent(folders: List<FolderDetail>, conversations: List<ConversationSummary>) =
        buildSessionSections(folders, conversations, SessionListPreferences(showRecent = false))

    private fun conv(
        id: Int,
        folderId: Int,
        updated: Long,
        pinnedAt: Long? = null,
    ) = ConversationSummary(
        id = id,
        folderId = folderId,
        title = "c$id",
        agentType = AgentType.CLAUDE_CODE,
        status = ConversationStatus.COMPLETED,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.ofEpochSecond(updated),
        pinnedAt = pinnedAt?.let { Instant.ofEpochSecond(it) },
    )

    private fun folder(id: Int, name: String, sortOrder: Int = 0, color: String = "") =
        FolderDetail(id = id, name = name, path = "/p/$id", sortOrder = sortOrder, color = color)

    @Test
    fun `hiding a root hides worktree and recent rows but preserves pinned and full source data`() {
        val folders = listOf(folder(1, "Root"), folder(2, "Branch").copy(parentId = 1), folder(3, "Visible"))
        val conversations = listOf(conv(1, 1, 10), conv(2, 2, 20), conv(3, 2, 30, pinnedAt = 40), conv(4, 3, 40))
        val sections = buildSessionSections(
            folders, conversations, SessionListPreferences(recentShowCompleted = true), hiddenFolderIds = setOf(1),
        )
        assertEquals(listOf("pinned", "folder-3", "recent"), sections.map { it.id })
        assertEquals(listOf(3), sections.first().rows.map { it.conversation.id })
        assertEquals("Root", sections.first().rows.single().folderName)
        assertEquals(listOf(4), sections.last().rows.map { it.conversation.id })
        assertEquals(4, conversations.size)
        assertEquals(listOf(2, 1), buildWithoutRecent(folders, conversations).first { it.id == "folder-1" }.rows.map { it.conversation.id })
    }

    @Test
    fun `hidden missing folder does not leak into Other and hiding one worktree keeps its siblings`() {
        val folders = listOf(folder(1, "Root"), folder(2, "Hidden branch").copy(parentId = 1), folder(3, "Branch").copy(parentId = 1))
        val conversations = listOf(conv(1, 1, 10), conv(2, 2, 20), conv(3, 3, 30), conv(4, 99, 40))
        val sections = buildSessionSections(
            folders, conversations, SessionListPreferences(showRecent = false), hiddenFolderIds = setOf(2, 99),
        )
        assertEquals(listOf("folder-1"), sections.map { it.id })
        assertEquals(listOf(3, 1), sections.single().rows.map { it.conversation.id })
    }

    @Test
    fun `sections are ordered pinned then folders then other`() {
        val folders = listOf(
            folder(1, "Beta", sortOrder = 1),
            folder(2, "Alpha", sortOrder = 0),
        )
        val convs = listOf(
            conv(10, folderId = 1, updated = 100, pinnedAt = 50),
            conv(11, folderId = 2, updated = 90, pinnedAt = 70),
            conv(20, folderId = 1, updated = 200),
            conv(21, folderId = 1, updated = 150),
            conv(30, folderId = 2, updated = 120),
            conv(40, folderId = 99, updated = 300), // orphan → Other
        )

        val sections = buildWithoutRecent(folders, convs)

        // sortedFolders orders by sortOrder then name → Alpha(0) before Beta(1).
        assertEquals(listOf("pinned", "folder-2", "folder-1", "other"), sections.map { it.id })
    }

    @Test
    fun `pinned section spans folders, sorts by pinnedAt desc, and tags the folder`() {
        val folders = listOf(folder(1, "Beta", sortOrder = 1), folder(2, "Alpha", sortOrder = 0))
        val convs = listOf(
            conv(10, folderId = 1, updated = 100, pinnedAt = 50),
            conv(11, folderId = 2, updated = 90, pinnedAt = 70),
            conv(30, folderId = 2, updated = 120),
        )

        val pinned = buildWithoutRecent(folders, convs).first { it.id == "pinned" }

        assertTrue(pinned.kind is SectionKind.Pinned)
        // pinnedAt 70 (conv 11) is more recent than 50 (conv 10).
        assertEquals(listOf(11, 10), pinned.rows.map { it.conversation.id })
        // Cross-folder rows carry the folder tag, pre-resolved.
        assertEquals(listOf("Alpha", "Beta"), pinned.rows.map { it.folderName })
    }

    @Test
    fun `folder group lists its non-pinned rows newest-first with no folder tag`() {
        val folders = listOf(folder(1, "Beta", sortOrder = 1), folder(2, "Alpha", sortOrder = 0))
        val convs = listOf(
            conv(11, folderId = 2, updated = 90, pinnedAt = 70), // pinned → NOT in folder-2
            conv(20, folderId = 1, updated = 200),
            conv(21, folderId = 1, updated = 150),
            conv(30, folderId = 2, updated = 120),
        )

        val sections = buildWithoutRecent(folders, convs)
        val beta = sections.first { it.id == "folder-1" }
        val alpha = sections.first { it.id == "folder-2" }

        assertEquals("Beta", (beta.kind as SectionKind.Folder).name)
        assertEquals(listOf(20, 21), beta.rows.map { it.conversation.id }) // newest-first
        assertTrue(beta.rows.all { it.folderName == null })               // header is the folder
        // The pinned conv 11 is excluded from its folder group; only conv 30 remains.
        assertEquals(listOf(30), alpha.rows.map { it.conversation.id })
    }

    @Test
    fun `empty folders do not fill the conversation list with headers`() {
        val sections = buildWithoutRecent(listOf(folder(1, "Solo")), emptyList())

        assertTrue(sections.isEmpty())
    }

    @Test
    fun `no pinned or other section when there are none`() {
        val sections = buildWithoutRecent(
            listOf(folder(1, "A")),
            listOf(conv(1, folderId = 1, updated = 10)),
        )

        assertEquals(listOf("folder-1"), sections.map { it.id })
    }

    @Test
    fun `orphan conversations fall into Other and resolve no folder tag`() {
        val sections = buildWithoutRecent(
            listOf(folder(1, "A")),
            listOf(conv(40, folderId = 99, updated = 300)),
        )
        val other = sections.first { it.id == "other" }

        assertTrue(other.kind is SectionKind.Other)
        assertEquals(listOf(40), other.rows.map { it.conversation.id })
        assertNull(other.rows.single().folderName) // unknown folder → no tag
    }

    @Test
    fun `per-chat scratch folders become one newest-first chat section`() {
        val folders = listOf(
            folder(1, "Chat").copy(kind = "chat"),
            folder(2, "Chat").copy(isChat = true), // older server
            folder(3, "Chat").copy(kind = "regular"), // a real project named Chat
        )
        val conversations = listOf(
            conv(1, 1, 10), conv(2, 2, 30), conv(3, 3, 20),
            conv(4, 99, 40).copy(kind = "chat"), // folder missing from the response
        )

        val sections = buildWithoutRecent(folders, conversations)

        assertEquals(listOf("chats", "folder-3"), sections.map { it.id })
        assertEquals(listOf(4, 2, 1), sections.first().rows.map { it.conversation.id })
        assertTrue(sections.first().rows.all { it.folderName == null })
        assertEquals(listOf(3), sections.last().rows.map { it.conversation.id })
    }

    @Test
    fun `pinned chats appear once and do not expose a scratch folder tag`() {
        val sections = buildWithoutRecent(
            listOf(folder(1, "Chat").copy(kind = "chat")),
            listOf(conv(1, 1, 10, pinnedAt = 20).copy(kind = "chat"), conv(2, 1, 30)),
        )

        assertEquals(listOf("pinned", "chats"), sections.map { it.id })
        assertEquals(listOf(1, 2), sections.flatMap { it.rows }.map { it.conversation.id })
        assertNull(sections.first().rows.single().folderName)
    }

    @Test
    fun `worktrees merge into their root while a missing root keeps its sessions reachable`() {
        val sections = buildWithoutRecent(
            listOf(
                folder(1, "Repo"),
                folder(2, "task-a").copy(parentId = 1),
                folder(3, "task-b").copy(parentId = 1),
                folder(4, "detached").copy(parentId = 999),
            ),
            listOf(conv(1, 1, 10), conv(2, 2, 30), conv(3, 3, 20), conv(4, 4, 40)),
        )

        assertEquals(listOf("folder-4", "folder-1"), sections.map { it.id })
        assertEquals(listOf(2, 3, 1), sections.last().rows.map { it.conversation.id })
        assertEquals(listOf(4), sections.first().rows.map { it.conversation.id })
    }

    @Test
    fun `all history remains available exactly once before display pagination`() {
        val folders = listOf(
            folder(1, "Chat").copy(kind = "chat"), folder(2, "Project"),
            folder(3, "branch").copy(parentId = 2), folder(4, "Empty"),
        )
        val conversations = (1..95).map { id ->
            conv(id, if (id % 4 == 0) 99 else id % 3 + 1, id.toLong(),
                pinnedAt = if (id % 10 == 0) id.toLong() else null)
        }

        val sections = buildWithoutRecent(folders, conversations)
        val rows = sections.flatMap { it.rows }.map { it.conversation.id }

        assertEquals(conversations.map { it.id }.sorted(), rows.sorted())
        assertEquals(rows.size, rows.distinct().size)
        assertTrue(sections.none { it.id == "folder-4" || it.id == "folder-3" || it.id == "folder-1" })
        assertTrue(sections.first { it.id == "chats" }.count > 20)
    }
}
