package app.codeg.android.core.model

/**
 * Pure folder-visibility + branch-switch routing, ported 1:1 from iOS
 * `FolderVisibility` (itself a port of the codeg web client's
 * `folder-display.ts` / `branch-switch.ts`).
 *
 * Worktree folders carry a [FolderDetail.parentId] (their root repo). These
 * helpers are deterministic (no I/O), so the session-detail view model can share
 * them and they're trivially unit-testable.
 */
object FolderVisibility {

    /** Hiding a project also hides its worktrees; keep the full folder map for routing. */
    fun hiddenIds(folders: List<FolderDetail>, hidden: Set<Int>): Set<Int> =
        hidden + folders.filter { it.parentId in hidden }.map { it.id }

    /** A folder is a worktree of a root repo when it has a parent. */
    fun isWorktree(folder: FolderDetail): Boolean = folder.parentId != null

    /**
     * Top-level folders only ([FolderDetail.parentId] null) — the new-session
     * folder picker must not target a worktree directly.
     */
    fun filterTopLevel(folders: List<FolderDetail>): List<FolderDetail> =
        folders.filter { it.parentId == null }

    /**
     * The root repo folder for [folder] (itself when top-level, or when its
     * parent isn't in [all]).
     */
    fun resolveRoot(folder: FolderDetail, all: List<FolderDetail>): FolderDetail {
        val pid = folder.parentId ?: return folder
        return all.firstOrNull { it.id == pid } ?: folder
    }

    /**
     * Display name for [folder]: the root repo's name when [folder] is a
     * worktree, else its own name.
     */
    fun displayName(folder: FolderDetail, all: List<FolderDetail>): String {
        val pid = folder.parentId ?: return folder.name
        return all.firstOrNull { it.id == pid }?.name ?: folder.name
    }

    // region Branch-switch routing

    /** Where a branch switch should land. Port of web `BranchSwitchPlan`. */
    sealed interface BranchSwitchPlan {
        /** Already on this branch in this folder — nothing to do. */
        data object Noop : BranchSwitchPlan
        /** Branch is checked out in an already-registered folder → open a session there. */
        data class NavigateRegistered(val folderId: Int) : BranchSwitchPlan
        /**
         * Branch is in an unregistered worktree dir → register it (parented to
         * [rootId]), then open a session there.
         */
        data class NavigateExternal(val path: String, val rootId: Int) : BranchSwitchPlan
        /**
         * Branch isn't checked out in any worktree (or it's a remote pick) →
         * `git checkout` in the repo root.
         */
        data class CheckoutInRoot(val root: FolderDetail) : BranchSwitchPlan
    }

    /**
     * Decide how to switch [active] to a branch, given the worktree [resolution]
     * (null for a remote pick or when resolution failed) and the full folder set.
     * Port of `planBranchSwitch`.
     */
    fun planBranchSwitch(
        active: FolderDetail,
        resolution: WorktreeResolution?,
        allFolders: List<FolderDetail>,
        isRemote: Boolean,
    ): BranchSwitchPlan {
        val root = resolveRoot(active, allFolders)
        // Remote selection or "not checked out anywhere" → checkout in the root.
        val path = resolution?.path
        if (isRemote || resolution == null || path == null) {
            return BranchSwitchPlan.CheckoutInRoot(root)
        }
        if (resolution.folderId == active.id) return BranchSwitchPlan.Noop
        resolution.folderId?.let { return BranchSwitchPlan.NavigateRegistered(it) }
        return BranchSwitchPlan.NavigateExternal(path, root.id)
    }

    // endregion
}
