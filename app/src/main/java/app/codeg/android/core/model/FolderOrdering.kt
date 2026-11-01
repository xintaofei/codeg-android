package app.codeg.android.core.model

/** Local order takes priority; new folders retain the server's default order at the end. */
object FolderOrdering {
    fun sorted(folders: List<FolderDetail>, order: List<Int> = emptyList()): List<FolderDetail> {
        val ranks = order.distinct().withIndex().associate { it.value to it.index }
        return folders.sortedWith(compareBy<FolderDetail> { ranks[it.id] ?: Int.MAX_VALUE }
            .thenBy { it.sortOrder }.thenBy { it.name.lowercase() }.thenBy { it.id })
    }

    /** Swap visible neighbors while preserving hidden folders' positions in the full order. */
    fun move(
        folders: List<FolderDetail>, order: List<Int>, hiddenIds: Set<Int>, folderId: Int, offset: Int,
    ): List<Int> {
        if (offset != -1 && offset != 1) return order
        val all = sorted(folders.filterNot { it.isChatFolder }, order)
        val hidden = FolderVisibility.hiddenIds(folders, hiddenIds)
        val visible = all.filter { it.id !in hidden }
        val index = visible.indexOfFirst { it.id == folderId }
        if (index < 0) return order
        val target = visible.getOrNull(index + offset) ?: return order
        val ids = all.map { it.id }.toMutableList()
        val from = ids.indexOf(folderId)
        val to = ids.indexOf(target.id)
        ids[from] = target.id
        ids[to] = folderId
        return ids
    }

    /** Replace only visible slots; hidden folders and newly arrived folders are retained. */
    fun reorder(
        folders: List<FolderDetail>, order: List<Int>, hiddenIds: Set<Int>, visibleOrder: List<Int>,
    ): List<Int> {
        val all = sorted(folders.filterNot { it.isChatFolder }, order)
        val hidden = FolderVisibility.hiddenIds(folders, hiddenIds)
        val visible = all.filter { it.id !in hidden }.map { it.id }
        val requested = visibleOrder.distinct().filter { it in visible }
        val replacement = (requested + visible.filterNot { it in requested }).iterator()
        return all.map { if (it.id in hidden) it.id else replacement.next() }
    }
}
