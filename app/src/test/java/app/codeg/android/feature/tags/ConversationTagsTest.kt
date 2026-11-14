package app.codeg.android.feature.tags

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.ConversationTag
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.feature.sessions.buildSessionSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ConversationTagsTest {

    private fun tag(id: Int, folder: Int? = null, order: Int = 0, name: String = "t$id", color: String = "#cf222e") =
        ConversationTag(id, folder, name, color, order)

    private fun conv(id: Int, folder: Int = 1, tags: List<Int> = emptyList(), branch: String? = null, parent: Int? = null) =
        ConversationSummary(
            id = id, folderId = folder, agentType = AgentType.CODEX, status = ConversationStatus.COMPLETED,
            gitBranch = branch, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, tagIds = tags, parentId = parent,
        )

    private fun folder(id: Int, parent: Int? = null, chat: Boolean = false) =
        FolderDetail(id = id, name = "f$id", path = "/f$id", parentId = parent, isChat = chat)

    @Test
    fun `a worktree is offered its repo's tags, a chat none of its own`() {
        assertEquals(1, ConversationTags.scopeFolderId(folder(1)))
        assertEquals(1, ConversationTags.scopeFolderId(folder(2, parent = 1)))
        assertNull(ConversationTags.scopeFolderId(folder(3, chat = true)))
        assertTrue(ConversationTags.fitsScope(tag(1), null))
        assertTrue(ConversationTags.fitsScope(tag(2, folder = 1), 1))
        assertFalse(ConversationTags.fitsScope(tag(3, folder = 2), 1))
    }

    @Test
    fun `tags read global first, then by folder in their order, and unknown ids are dropped`() {
        val byId = listOf(tag(5, folder = 2, order = 1), tag(4, order = 2), tag(3, order = 1), tag(2, folder = 1, order = 1)).associateBy { it.id }
        assertEquals(listOf(3, 4, 2, 5), ConversationTags.resolve(listOf(5, 2, 4, 3, 99), byId).map { it.id })
    }

    @Test
    fun `a new tag takes the first strong colour its neighbours leave free`() {
        assertEquals("#cf222e", ConversationTags.nextColor(emptyList()))
        assertEquals("#bc4c00", ConversationTags.nextColor(listOf(tag(1, color = "#CF222E"))))
        val all = ConversationTags.COLOR_PRESETS.take(9).mapIndexed { i, c -> tag(i, color = c) }
        assertEquals(ConversationTags.COLOR_PRESETS[0], ConversationTags.nextColor(all))
    }

    @Test
    fun `colours normalize the server's way`() {
        assertEquals("#aabbcc", ConversationTags.normalizeColor(" #ABC "))
        assertEquals("#a1b2c3", ConversationTags.normalizeColor("#A1B2C3"))
        assertNull(ConversationTags.normalizeColor("red"))
        assertNull(ConversationTags.normalizeColor("#abcd"))
    }

    @Test
    fun `names match case-insensitively within a scope only`() {
        val tags = listOf(tag(1, name = "Bug"), tag(2, folder = 1, name = "review"))
        assertEquals(1, ConversationTags.findByName(tags, null, " bug ")?.id)
        assertNull(ConversationTags.findByName(tags, 1, "bug"))
        assertEquals(2, ConversationTags.findByName(tags, 1, "REVIEW")?.id)
    }

    @Test
    fun `a filter matches any or all, toggles in order, and forgets deleted tags`() {
        val c = conv(1, tags = listOf(1, 2))
        assertTrue(TagFilter(listOf(1, 3)).matches(c))
        assertFalse(TagFilter(listOf(1, 3), TagFilterMode.ALL).matches(c))
        assertTrue(TagFilter(listOf(2, 1), TagFilterMode.ALL).matches(c))
        assertFalse(TagFilter(listOf(1)).matches(conv(2)))
        assertTrue(TagFilter.NONE.matches(conv(2)))
        assertEquals(listOf(3, 1), TagFilter(listOf(3)).toggle(1).tagIds)
        assertEquals(listOf(3), TagFilter(listOf(3, 1)).toggle(1).tagIds)
        val kept = TagFilter(listOf(1, 2))
        assertSame(kept, kept.pruned(setOf(1, 2, 3)))
        assertEquals(listOf(2), kept.pruned(setOf(2)).tagIds)
    }

    @Test
    fun `a filtered list drops the folders it empties and resolves each row's chips`() {
        val folders = listOf(folder(1), folder(2))
        val convs = listOf(conv(1, folder = 1, tags = listOf(7), branch = "main"), conv(2, folder = 2))
        val tags = mapOf(7 to tag(7))
        val all = buildSessionSections(folders, convs, tags, branchTagEnabled = true)
        assertEquals(2, all.size)
        val row = all.first().rows.single()
        assertEquals(listOf(7), row.tags.map { it.id })
        assertEquals("main", row.branchChip)
        val filtered = buildSessionSections(folders, convs, tags, branchTagEnabled = false, filter = TagFilter(listOf(7)))
        assertEquals(listOf("folder-1"), filtered.map { it.id })
        assertNull(filtered.single().rows.single().branchChip)
    }

    @Test
    fun `a sub-session shows no branch chip`() {
        assertNull(ConversationTags.branchChip(conv(1, branch = "main", parent = 9), enabled = true))
        assertEquals("main", ConversationTags.branchChip(conv(1, branch = " main "), enabled = true))
        assertNull(ConversationTags.branchChip(conv(1, branch = "  "), enabled = true))
    }

    @Test
    fun `chips stay readable in both themes`() {
        // Light: the colour fills, white text on a dark colour, black on a pale one.
        val red = TagSwatch.of("#cf222e", dark = false)!!
        assertEquals(0xFFCF222EL, red.background)
        assertEquals(0xFFFFFFFFL, red.foreground)
        assertEquals(0xFF000000L, TagSwatch.of("#fde68a", dark = false)!!.foreground)
        // Only the near-white get a rim in light.
        assertEquals(0L, red.border ushr 24)
        assertTrue((TagSwatch.of("#ffffff", dark = false)!!.border ushr 24) > 0)
        // Dark: the colour at 18% fills; the text is the colour lifted.
        val dark = TagSwatch.of("#0969da", dark = true)!!
        assertEquals(0x2E, (dark.background ushr 24).toInt())
        assertEquals(0xFF, (dark.foreground ushr 24).toInt())
        assertNull(TagSwatch.of("nope", dark = true))
    }
}
