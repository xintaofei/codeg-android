package app.codeg.android.feature.sessions

import app.codeg.android.core.datastore.RecentSessionFilter
import app.codeg.android.core.datastore.SessionListPreferences
import app.codeg.android.core.datastore.SessionSortMode
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SessionListOptionsTest {
    private val folders = listOf(
        FolderDetail(1, "Project", "/project"),
        FolderDetail(2, "Chat", "/chat", kind = "chat"),
        FolderDetail(3, "Closed project", "/closed"),
        FolderDetail(4, "Branch", "/branch", parentId = 1),
    )

    private fun conversation(id: Int, folderId: Int = 1, created: Long = id.toLong(), updated: Long = id.toLong()) =
        ConversationSummary(
            id = id, folderId = folderId, agentType = AgentType.CLAUDE_CODE,
            status = ConversationStatus.PENDING_REVIEW,
            createdAt = Instant.ofEpochSecond(created), updatedAt = Instant.ofEpochSecond(updated),
        )

    private fun sections(
        conversations: List<ConversationSummary>,
        preferences: SessionListPreferences = SessionListPreferences(),
    ) = buildSessionSections(folders, conversations, preferences, openFolderIds = setOf(1, 4))

    private fun List<SessionSection>.ids(section: String) = first { it.id == section }.rows.map { it.conversation.id }

    @Test
    fun `project sort switches while chats pinned and recent keep their own ordering`() {
        val conversations = listOf(
            conversation(1, created = 100, updated = 400),
            conversation(2, 4, created = 200, updated = 300),
            conversation(3, 2, created = 300, updated = 500),
            conversation(4, 2, created = 400, updated = 200),
            conversation(5, updated = 100).copy(pinnedAt = Instant.ofEpochSecond(900)),
            conversation(6, updated = 900).copy(pinnedAt = Instant.ofEpochSecond(800)),
        )
        val created = sections(conversations)
        val updated = sections(conversations, SessionListPreferences(sortMode = SessionSortMode.UPDATED))

        assertEquals(listOf(2, 1), created.ids("folder-1"))
        assertEquals(listOf(1, 2), updated.ids("folder-1"))
        for (result in listOf(created, updated)) {
            assertEquals(listOf(3, 4), result.ids("chats"))
            assertEquals(listOf(5, 6), result.ids("pinned"))
            assertEquals(listOf(3, 1, 2, 4), result.ids("recent"))
            assertEquals("Project", result.first { it.id == "recent" }.rows.first { it.conversation.id == 2 }.folderName)
        }
        assertEquals(Instant.ofEpochSecond(200), created.first { it.id == "folder-1" }.rows.first().timestamp)
        assertEquals(Instant.ofEpochSecond(400), updated.first { it.id == "folder-1" }.rows.first().timestamp)
        assertEquals(Instant.ofEpochSecond(500), created.first { it.id == "recent" }.rows.first().timestamp)
        assertEquals(Instant.ofEpochSecond(500), created.first { it.id == "chats" }.rows.first().timestamp)
    }

    @Test
    fun `time ties use the other timestamp then id for deterministic web ordering`() {
        val conversations = listOf(
            conversation(1, created = 10, updated = 40),
            conversation(2, created = 20, updated = 30),
            conversation(3, created = 20, updated = 40),
            conversation(4, created = 20, updated = 40),
        )
        assertEquals(listOf(4, 3, 2, 1), sections(conversations).ids("folder-1"))
        assertEquals(listOf(4, 3, 1, 2), sections(conversations).ids("recent"))
        assertEquals(listOf(4, 3, 1, 2), sections(conversations, SessionListPreferences(sortMode = SessionSortMode.UPDATED)).ids("folder-1"))
        val pinned = conversations.map { it.copy(pinnedAt = Instant.EPOCH) }
        assertEquals(listOf(4, 3, 2, 1), sections(pinned).ids("pinned"))
    }

    @Test
    fun `recent keeps old history but excludes pinned completed closed projects and orphans`() {
        val conversations = listOf(
            conversation(1), // All dates are in 1970: no 24-hour cutoff.
            conversation(2, 2), // Legacy chat folder flag works without conversation.kind.
            conversation(3, 99).copy(kind = "chat"),
            conversation(4).copy(pinnedAt = Instant.EPOCH),
            conversation(5).copy(status = ConversationStatus.COMPLETED),
            conversation(6, 3),
            conversation(7, 99),
            conversation(8, 4),
        )
        val result = sections(conversations)
        assertEquals(listOf(8, 3, 2, 1), result.ids("recent"))
        assertEquals(listOf(8, 5, 3, 2, 1), sections(conversations, SessionListPreferences(recentShowCompleted = true)).ids("recent"))
        assertEquals(conversations.map { it.id }.sorted(), result.filter { it.kind != SectionKind.Recent }.flatMap { it.rows }.map { it.conversation.id }.sorted())
    }

    @Test
    fun `recent filters narrow only recent and cli source can cross chat and project`() {
        val conversations = listOf(
            conversation(1), conversation(2, 2),
            conversation(3).copy(source = "cli"), conversation(4, 2).copy(source = "cli"),
        )
        val expected = mapOf(
            RecentSessionFilter.ALL to listOf(4, 3, 2, 1),
            RecentSessionFilter.CHATS to listOf(4, 2),
            RecentSessionFilter.FOLDERS to listOf(3, 1),
            RecentSessionFilter.CLI to listOf(4, 3),
        )
        for ((filter, ids) in expected) {
            val result = sections(conversations, SessionListPreferences(recentFilter = filter))
            assertEquals(ids, result.ids("recent"))
            assertEquals(listOf(3, 1), result.ids("folder-1"))
            assertEquals(listOf(4, 2), result.ids("chats"))
        }
    }

    @Test
    fun `recent visibility and preview sizes never truncate the underlying history`() {
        val conversations = (1..65).map { conversation(it) }
        val visible = sections(conversations)
        val recent = visible.first { it.kind == SectionKind.Recent }
        val project = visible.first { it.kind is SectionKind.Folder }
        assertEquals(10, recent.pageSize)
        assertEquals(20, project.pageSize)
        assertEquals(65, recent.count)
        assertEquals(65, project.count)
        val hidden = sections(conversations, SessionListPreferences(showRecent = false))
        assertFalse(hidden.any { it.kind == SectionKind.Recent })
        assertEquals(visible.filter { it.kind != SectionKind.Recent }, hidden)
        assertEquals(65, conversations.size)
    }

    @Test
    fun `empty recent stays discoverable when filter has no matches`() {
        val result = sections(listOf(conversation(1)), SessionListPreferences(recentFilter = RecentSessionFilter.CLI))
        assertTrue(result.first { it.id == "recent" }.rows.isEmpty())
        assertEquals(listOf(1), result.ids("folder-1"))
    }
}
