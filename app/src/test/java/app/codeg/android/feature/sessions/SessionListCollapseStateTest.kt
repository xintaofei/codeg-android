package app.codeg.android.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListCollapseStateTest {
    @Test
    fun `restore returns to the mixed layout instead of expanding every group`() {
        val original = SessionListCollapseState().toggleSection("chats").toggleSection("folder-1")
        val compact = original.toggleCompact()
        assertTrue(compact.isCollapsed("chats"))
        assertTrue(compact.isCollapsed("folder-1"))
        assertTrue(compact.isCollapsed("recent"))

        val restored = compact.toggleCompact()
        assertEquals(original, restored)
        assertTrue(restored.isCollapsed("chats"))
        assertTrue(restored.isCollapsed("folder-1"))
        assertFalse(restored.isCollapsed("recent"))
    }

    @Test
    fun `groups added by refresh stay collapsed during compact mode`() {
        val compact = SessionListCollapseState().toggleCompact()
        assertTrue(compact.isCollapsed("new-project"))
        assertFalse(compact.toggleCompact().isCollapsed("new-project"))
    }

    @Test
    fun `opening a group in compact mode is temporary and preserves the original layout`() {
        val original = SessionListCollapseState().toggleSection("chats")
        val compact = original.toggleCompact().toggleSection("chats")
        assertFalse(compact.isCollapsed("chats"))
        assertTrue(compact.isCollapsed("recent"))
        assertTrue(compact.toggleSection("chats").isCollapsed("chats"))
        assertEquals(original, compact.toggleCompact())
    }

    @Test
    fun `each compact cycle restores the most recent normal layout`() {
        val original = SessionListCollapseState().toggleSection("chats")
        val changed = original.toggleCompact().toggleCompact().toggleSection("recent")
        assertEquals(changed, changed.toggleCompact().toggleCompact())
        assertTrue(changed.isCollapsed("recent"))
    }

}
