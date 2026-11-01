package app.codeg.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceDownloadRequest(val rootPath: String, val path: String, val kind: String = "file")

@Serializable
data class WorkspaceDownloadTicket(val url: String, val filename: String)
