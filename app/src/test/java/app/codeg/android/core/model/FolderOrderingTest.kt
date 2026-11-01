package app.codeg.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderOrderingTest {
    private fun folder(id: Int, sortOrder: Int = id, parentId: Int? = null) =
        FolderDetail(id, "f$id", "/f$id", sortOrder = sortOrder, parentId = parentId)

    @Test
    fun `no custom order uses the server order and resolves ties deterministically`() {
        val folders = listOf(folder(3, 2), folder(2, 1), folder(1, 1))
        assertEquals(listOf(1, 2, 3), FolderOrdering.sorted(folders).map { it.id })
    }

    @Test
    fun `custom order ignores duplicates and removed ids and appends new folders without losing source data`() {
        val folders = listOf(folder(1), folder(2), folder(3), folder(4))
        val ordered = FolderOrdering.sorted(folders, listOf(3, 99, 3, 1))
        assertEquals(listOf(3, 1, 2, 4), ordered.map { it.id })
        assertEquals(folders.toSet(), ordered.toSet())
        assertEquals(listOf(1, 2, 3, 4), folders.map { it.id })
    }

    @Test
    fun `moving visible neighbors preserves hidden projects and their worktree positions`() {
        val folders = (1..5).map { folder(it, parentId = if (it == 3) 2 else null) } +
            folder(6).copy(kind = "chat")
        val moved = FolderOrdering.move(folders, emptyList(), setOf(2), 4, -1)
        assertEquals(listOf(4, 2, 3, 1, 5), moved)
        val restored = FolderOrdering.sorted(folders.filterNot { it.isChatFolder }, moved)
        assertEquals(listOf(4, 2, 3, 1, 5), restored.map { it.id })
    }

    @Test
    fun `boundary invalid hidden and chat moves are noops`() {
        val folders = listOf(folder(1), folder(2), folder(3).copy(kind = "chat"))
        val order = listOf(1, 2)
        assertEquals(order, FolderOrdering.move(folders, order, emptySet(), 1, -1))
        assertEquals(order, FolderOrdering.move(folders, order, emptySet(), 2, 1))
        assertEquals(order, FolderOrdering.move(folders, order, emptySet(), 1, 2))
        assertEquals(order, FolderOrdering.move(folders, order, setOf(1), 1, 1))
        assertEquals(order, FolderOrdering.move(folders, order, emptySet(), 3, -1))
        assertEquals(order, FolderOrdering.move(folders, order, emptySet(), 99, -1))
    }

    @Test
    fun `dragging across multiple rows preserves hidden slots and includes new folders`() {
        val folders = (1..6).map { folder(it, parentId = if (it == 3) 2 else null) } +
            folder(7).copy(kind = "chat")
        assertEquals(listOf(5, 2, 3, 1, 4, 6),
            FolderOrdering.reorder(folders, emptyList(), setOf(2), listOf(5, 99, 5, 2, 7, 1, 4)))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), folders.filterNot { it.isChatFolder }.map { it.id })
    }
}
