package app.codeg.android.feature.projects

import app.codeg.android.core.model.FolderDetail
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectsVisibilityTest {
    @Test
    fun `hidden projects and their worktrees disappear and restore without losing folder context`() {
        val folders = listOf(
            FolderDetail(1, "Root", "/root"),
            FolderDetail(2, "Branch", "/branch", parentId = 1),
            FolderDetail(3, "Other", "/other"),
        )
        val hidden = ProjectsUiState(folders = folders, hiddenFolderIds = setOf(1))
        assertEquals(listOf(3), hidden.visibleFolders.map { it.id })
        assertEquals(folders, hidden.folders)
        assertEquals(folders, hidden.copy(hiddenFolderIds = emptySet()).visibleFolders)
    }
}
