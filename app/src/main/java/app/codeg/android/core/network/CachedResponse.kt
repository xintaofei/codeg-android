package app.codeg.android.core.network

import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderDetail
import kotlinx.serialization.Serializable

data class CachedResponse<T>(val value: T, val savedAt: Long, val snapshot: String) {
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean = savedAt > 0 && now - savedAt in 0 until 60_000
}

@Serializable
data class SessionListSnapshot(
    val folders: List<FolderDetail>,
    val conversations: List<ConversationSummary>,
    val openFolderIds: Set<Int>,
)
