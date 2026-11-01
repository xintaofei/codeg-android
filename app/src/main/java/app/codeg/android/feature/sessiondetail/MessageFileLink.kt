package app.codeg.android.feature.sessiondetail

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal data class WorkspaceFileLink(val rootPath: String, val path: String)
internal data class PreparedDownload(val url: String, val filename: String)

internal fun messageWebUrl(link: String): URI? = runCatching {
    URI(link.trim().removeSurrounding("<", ">")).takeIf {
        it.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null
    }
}.getOrNull()

internal fun isApkLink(link: String): Boolean = messageWebUrl(link)?.path?.endsWith(".apk", ignoreCase = true) == true

/** Absolute paths are server paths, never paths on the phone. */
internal fun workspaceFileLink(link: String, roots: List<String>, currentRoot: String? = roots.firstOrNull()): WorkspaceFileLink? {
    var raw = link.trim().removeSurrounding("<", ">").substringBefore('#')
    if (raw.startsWith("file:", ignoreCase = true)) {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.authority.isNullOrEmpty() && uri.authority != "localhost") return null
        raw = uri.rawPath ?: return null
    } else if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw) && !Regex("^[a-zA-Z]:[/\\\\]").containsMatchIn(raw)) {
        return null
    }
    raw = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
    raw = raw.replace(Regex(":\\d+(?::\\d+)?$"), "")
    if (raw.isBlank() || raw.any { it.isISOControl() }) return null
    val normalizedRoots = roots.mapNotNull { root -> normalizeServerPath(root)?.let { root to it } }
    val absolute = raw.startsWith('/') || Regex("^[a-zA-Z]:[/\\\\]").containsMatchIn(raw)
    val relativeRoot = currentRoot?.let(::normalizeServerPath)
    val target = normalizeServerPath(if (absolute) raw else "${relativeRoot ?: return null}/$raw") ?: return null
    val candidates = if (absolute) normalizedRoots else normalizedRoots.filter { it.second == relativeRoot }
    val match = candidates.sortedByDescending { it.second.length }.firstOrNull { (_, root) ->
        target.startsWith("${root.trimEnd('/')}/")
    }
    if (match != null) {
        val (original, root) = match
        return WorkspaceFileLink(original, target.removePrefix(root.trimEnd('/') + "/"))
    }
    // Generated artifacts can live outside projects (for example, the server's Downloads folder).
    // Use only the explicitly linked file's parent; the authenticated server validates the file.
    if (!absolute || raw.endsWith('/') || raw.endsWith('\\') || raw.replace('\\', '/').split('/').any { it == ".." }) return null
    val filename = target.substringAfterLast('/')
    if (filename.isBlank() || Regex("^[a-zA-Z]:$").matches(filename)) return null
    val parent = target.substringBeforeLast('/', "").let {
        when {
            it.isEmpty() && target.startsWith('/') -> "/"
            Regex("^[a-zA-Z]:$").matches(it) -> "$it/"
            else -> it
        }
    }
    return parent.takeIf { it.isNotEmpty() }?.let { WorkspaceFileLink(it, filename) }
}

private fun normalizeServerPath(path: String): String? {
    val windows = Regex("^/?[a-zA-Z]:[/\\\\]").containsMatchIn(path)
    val raw = if (windows) path.removePrefix("/").replace('\\', '/') else path
    if (!raw.startsWith('/') && !windows) return null
    val parts = mutableListOf<String>()
    for (part in raw.split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (parts.isEmpty() || (windows && parts.size == 1)) return null else parts.removeAt(parts.lastIndex)
        else -> parts += part
    }
    return (if (windows) "" else "/") + parts.joinToString("/")
}

internal fun safeDownloadFilename(name: String): String {
    val clean = name.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.isISOControl() || it in "<>:\"|?*" }.trim().trimStart('.')
    val extension = clean.substringAfterLast('.', "").takeIf { it.length in 1..12 }?.let { ".$it" }.orEmpty()
    var stem = clean.removeSuffix(extension)
    while ((stem + extension).toByteArray(Charsets.UTF_8).size > 180 && stem.isNotEmpty()) {
        stem = stem.substring(0, stem.offsetByCodePoints(stem.length, -1))
    }
    return (stem + extension).ifBlank { "download" }
}
