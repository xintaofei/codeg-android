package app.codeg.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionFile(
    val path: String,
    val operation: String,
    val turnId: String,
    val timestamp: String,
    /** Null when an older server cannot check the path until it is opened. */
    val available: Boolean? = null,
    val size: Long? = null,
) {
    val filename: String get() = path.substringAfterLast('/').substringAfterLast('\\')
}
