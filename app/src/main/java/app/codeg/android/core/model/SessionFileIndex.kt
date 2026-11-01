package app.codeg.android.core.model

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Compatibility index for servers without get_conversation_files. Never scans the phone's filesystem. */
internal fun collectSessionFiles(turns: List<MessageTurn>, root: String): List<SessionFile> {
    val failed = turns.flatMap { it.blocks }.filterIsInstance<ContentBlock.ToolResult>()
        .filter { it.isError }.mapNotNull { it.id }.toSet()
    val files = linkedMapOf<String, SessionFile>()
    for (turn in turns) {
        val paths = mutableListOf<Pair<String, String>>()
        for (block in turn.blocks) when (block) {
            is ContentBlock.ToolUse -> {
                if (block.id in failed || block.status in setOf("failed", "pending", "in_progress")) continue
                val operation = fileToolOperation(block.name) ?: continue
                val input = block.inputPreview ?: continue
                val parsed = runCatching { Json.parseToJsonElement(input) }.getOrNull()
                if (parsed != null) collectInputPaths(parsed, operation, paths)
                else if (operation == "apply_patch") collectPatchPaths(input, paths)
                else PATH_FIELD.find(input)?.groupValues?.get(1)?.let { encoded ->
                    runCatching { Json.decodeFromString<String>(encoded) }.getOrNull()?.let { paths += it to operation }
                }
            }
            is ContentBlock.Text -> if (turn.role == TurnRole.ASSISTANT) {
                for (match in LOCAL_LINK.findAll(block.text)) paths += (match.groupValues[1].ifEmpty { match.groupValues[2] }) to "referenced"
            }
            is ContentBlock.ImageGeneration -> block.image?.uri?.let { paths += it to "write" }
            else -> Unit
        }
        for ((raw, operation) in paths) {
            val path = sessionFilePath(raw, root, operation == "referenced" || raw.startsWith("file:")) ?: continue
            val actualOperation = if (operation == "referenced") files[path]?.operation ?: operation else operation
            files[path] = SessionFile(path, actualOperation, turn.id, turn.timestamp.toString())
        }
    }
    return files.values.sortedWith(compareByDescending<SessionFile> { Instant.parse(it.timestamp) }.thenBy { it.path })
}

private fun fileToolOperation(name: String): String? = when (
    name.substringAfterLast('.').substringAfterLast(':').substringAfterLast("__").lowercase(Locale.ROOT)
) {
    "write", "write_file", "create_file", "save_file" -> "write"
    "edit", "edit_file", "multiedit", "multi_edit", "replace", "replace_in_file", "notebookedit" -> "edit"
    "apply_patch", "applypatch", "patch" -> "apply_patch"
    else -> null
}

private val PATH_KEYS = listOf("file_path", "filePath", "path", "target_file", "targetFile", "filename", "notebook_path")
private val PATH_FIELD = Regex(""""(?:file_path|filePath|path|target_file|targetFile|filename|notebook_path)"\s*:\s*("(?:[^"\\]|\\.)*")""")
private val LOCAL_LINK = Regex("""\]\(\s*(?:<([^>]+)>|([^\s)]+))(?:\s+"[^"]*")?\s*\)""")
private val LOCATION = Regex(":\\d+(?::\\d+)?$")

private fun collectInputPaths(value: JsonElement, operation: String, paths: MutableList<Pair<String, String>>, depth: Int = 0) {
    if (depth > 6) return
    if (value is JsonPrimitive && value.isString) {
        val text = value.content
        if (operation == "apply_patch") collectPatchPaths(text, paths)
        runCatching { Json.parseToJsonElement(text) }.getOrNull()?.let { collectInputPaths(it, operation, paths, depth + 1) }
    }
    val obj = value as? JsonObject ?: return
    for (key in PATH_KEYS) (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { paths += it to operation }
    val changes = obj["changes"] as? JsonObject
    if (changes != null) {
        paths += changes.keys.map { it to "edit" }
        return
    }
    for (key in listOf("input", "arguments", "params", "payload")) obj[key]?.let { collectInputPaths(it, operation, paths, depth + 1) }
    // An edit's content can itself contain patch examples; inspect only real patch inputs.
    if (operation == "apply_patch") for (key in listOf("patch", "content", "diff", "command", "cmd")) {
        (obj[key] as? JsonPrimitive)?.contentOrNull?.let { collectPatchPaths(it, paths) }
    }
}

private fun collectPatchPaths(text: String, paths: MutableList<Pair<String, String>>) {
    val prefixes = listOf("*** Add File: " to "write", "*** Update File: " to "edit", "*** Delete File: " to "delete", "*** Move to: " to "edit", "+++ b/" to "edit")
    for (line in text.lineSequence()) for ((prefix, operation) in prefixes) {
        if (line.startsWith(prefix)) paths += line.removePrefix(prefix) to operation
    }
}

private fun sessionFilePath(input: String, root: String, link: Boolean): String? {
    var raw = input.trim()
    if (raw.isBlank() || raw.startsWith('#')) return null
    if (raw.startsWith("file:", ignoreCase = true)) {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.authority.isNullOrEmpty() && uri.authority != "localhost") return null
        raw = uri.rawPath ?: return null
    } else if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw) && !Regex("^[a-zA-Z]:[/\\\\]").containsMatchIn(raw)) return null
    if (link) {
        raw = runCatching { URLDecoder.decode(raw.substringBefore('#').replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        raw = raw.replace(LOCATION, "")
    }
    if (raw.isBlank() || raw.any { it.isISOControl() } || raw.endsWith('/') || raw.endsWith('\\')) return null
    val windows = Regex("^/?[a-zA-Z]:[/\\\\]")
    val absolute = raw.startsWith('/') || windows.containsMatchIn(raw)
    if (!absolute && root.isBlank()) return null
    val joined = if (absolute) raw else "${root.trimEnd('/', '\\')}/$raw"
    val normalized = if (windows.containsMatchIn(joined)) joined.removePrefix("/").replace('\\', '/') else joined
    if (!normalized.startsWith('/') && !windows.containsMatchIn(normalized)) return null
    val parts = mutableListOf<String>()
    for (part in normalized.split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (parts.isEmpty() || (windows.containsMatchIn(normalized) && parts.size == 1)) return null else parts.removeAt(parts.lastIndex)
        else -> parts += part
    }
    return (if (normalized.startsWith('/')) "/" else "") + parts.joinToString("/")
}
