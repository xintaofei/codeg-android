package app.codeg.android.core.model

import app.codeg.android.core.model.FolderVisibility.BranchSwitchPlan
import org.junit.Assert.assertEquals
import org.junit.Test

/** Locks down the workspace-list visibility and worktree-aware branch-switch routing ported from iOS `FolderVisibility`. */
class FolderVisibilityTest {

    private fun folder(id: Int, name: String = "f$id", parentId: Int? = null) =
        FolderDetail(id = id, name = name, path = "/p/$id", parentId = parentId)

    @Test
    fun `filterTopLevel keeps only parentless folders`() {
        val all = listOf(folder(1), folder(2, parentId = 1), folder(3))
        assertEquals(listOf(1, 3), FolderVisibility.filterTopLevel(all).map { it.id })
    }

    @Test
    fun `resolveRoot returns self for a top-level folder`() {
        val root = folder(1)
        assertEquals(root, FolderVisibility.resolveRoot(root, listOf(root)))
    }

    @Test
    fun `resolveRoot returns the parent for a worktree`() {
        val root = folder(1)
        val wt = folder(2, parentId = 1)
        assertEquals(root, FolderVisibility.resolveRoot(wt, listOf(root, wt)))
    }

    @Test
    fun `resolveRoot falls back to self when the parent is absent`() {
        val wt = folder(2, parentId = 99)
        assertEquals(wt, FolderVisibility.resolveRoot(wt, listOf(wt)))
    }

    @Test
    fun `displayName uses the root repo name for a worktree`() {
        val root = folder(1, name = "repo")
        val wt = folder(2, name = "repo-wt", parentId = 1)
        assertEquals("repo", FolderVisibility.displayName(wt, listOf(root, wt)))
    }

    @Test
    fun `displayName uses own name for a top-level folder`() {
        val root = folder(1, name = "repo")
        assertEquals("repo", FolderVisibility.displayName(root, listOf(root)))
    }

    @Test
    fun `childToParent maps only worktrees whose root is open`() {
        val open = listOf(folder(1), folder(2, parentId = 1), folder(3, parentId = 99))
        assertEquals(mapOf(2 to 1), FolderVisibility.childToParent(open))
    }

    @Test
    fun `visibleFolders hides worktrees of an open root and chat folders`() {
        val open = listOf(
            folder(1),
            folder(2, parentId = 1), // worktree, root open → folds into 1
            folder(3, parentId = 99), // worktree, root closed → stands on its own
            folder(4).copy(kind = "chat"),
            folder(5).copy(isChat = true),
        )
        assertEquals(listOf(1, 3), FolderVisibility.visibleFolders(open).map { it.id })
    }

    @Test
    fun `mergedFolderId redirects a hidden worktree to its root`() {
        val map = mapOf(2 to 1)
        assertEquals(1, FolderVisibility.mergedFolderId(2, map))
        assertEquals(3, FolderVisibility.mergedFolderId(3, map))
    }

    @Test
    fun `remote pick always checks out in the root`() {
        val root = folder(1)
        // Even with a resolution present, a remote pick checks out fresh in the root.
        val plan = FolderVisibility.planBranchSwitch(
            active = root,
            resolution = WorktreeResolution(path = "/p/2", folderId = 2),
            allFolders = listOf(root, folder(2)),
            isRemote = true,
        )
        assertEquals(BranchSwitchPlan.CheckoutInRoot(root), plan)
    }

    @Test
    fun `unresolved branch checks out in the root`() {
        val root = folder(1)
        assertEquals(
            BranchSwitchPlan.CheckoutInRoot(root),
            FolderVisibility.planBranchSwitch(root, resolution = null, allFolders = listOf(root), isRemote = false),
        )
        // path == null means "not checked out anywhere" → also checkout in root.
        assertEquals(
            BranchSwitchPlan.CheckoutInRoot(root),
            FolderVisibility.planBranchSwitch(root, WorktreeResolution(path = null, folderId = null), listOf(root), false),
        )
    }

    @Test
    fun `branch already in the active folder is a noop`() {
        val root = folder(1)
        val plan = FolderVisibility.planBranchSwitch(
            active = root,
            resolution = WorktreeResolution(path = "/p/1", folderId = 1),
            allFolders = listOf(root),
            isRemote = false,
        )
        assertEquals(BranchSwitchPlan.Noop, plan)
    }

    @Test
    fun `branch in another registered folder navigates there`() {
        val root = folder(1)
        val other = folder(2)
        val plan = FolderVisibility.planBranchSwitch(
            active = root,
            resolution = WorktreeResolution(path = "/p/2", folderId = 2),
            allFolders = listOf(root, other),
            isRemote = false,
        )
        assertEquals(BranchSwitchPlan.NavigateRegistered(2), plan)
    }

    @Test
    fun `branch in an unregistered worktree navigates external with the root id`() {
        val root = folder(1)
        val active = folder(2, parentId = 1) // we're inside a worktree; root is 1
        val plan = FolderVisibility.planBranchSwitch(
            active = active,
            resolution = WorktreeResolution(path = "/ext/wt", folderId = null),
            allFolders = listOf(root, active),
            isRemote = false,
        )
        assertEquals(BranchSwitchPlan.NavigateExternal("/ext/wt", rootId = 1), plan)
    }
}
