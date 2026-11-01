package app.codeg.android.feature.downloads

import app.codeg.android.core.datastore.ServerProfile
import kotlinx.serialization.Serializable

@Serializable
internal enum class FileDownloadStatus { PREPARING, QUEUED, DOWNLOADING, PAUSED, COMPLETE, FAILED, CANCELED }

@Serializable
internal data class DownloadRecord(
    val key: String,
    val link: String,
    val filename: String,
    val serverId: String,
    val serverUrl: String,
    val serverName: String,
    val conversationId: Int? = null,
    val conversationTitle: String = "",
    val folderPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val id: Long? = null,
    val status: FileDownloadStatus = FileDownloadStatus.PREPARING,
    val bytes: Long = 0,
    val total: Long = -1,
    val error: String? = null,
) {
    val busy: Boolean get() = status in setOf(FileDownloadStatus.PREPARING, FileDownloadStatus.QUEUED, FileDownloadStatus.DOWNLOADING, FileDownloadStatus.PAUSED)
    val apk: Boolean get() = filename.endsWith(".apk", ignoreCase = true)
    fun belongsTo(profile: ServerProfile): Boolean = serverId == profile.id &&
        serverUrl.trim().trimEnd('/') == profile.baseUrl.trim().trimEnd('/')

    fun restored(interruptedMessage: String): DownloadRecord =
        if (busy && id == null) copy(status = FileDownloadStatus.FAILED, error = interruptedMessage) else this
}

internal fun List<DownloadRecord>.activeDownload(profile: ServerProfile, conversationId: Int?, link: String): DownloadRecord? =
    firstOrNull { it.belongsTo(profile) && it.conversationId == conversationId && it.link == link && it.busy }
