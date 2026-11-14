package app.codeg.android.feature.sessiondetail.interactive

import androidx.annotation.StringRes
import app.codeg.android.R
import app.codeg.android.core.designsystem.diff.DiffFile
import app.codeg.android.core.designsystem.diff.UnifiedDiff
import app.codeg.android.core.model.PermissionOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

data class PermissionPlanEntry(val text: String, val status: String?)
data class PermissionAllowedPrompt(val prompt: String, val tool: String)

/**
 * Everything the permission card needs, extracted from the freeform `tool_call`
 * JSON of a `permission_request`. Faithful port of the iOS `ParsedPermission` /
 * `PermissionParse` (which mirrors the web `parsePermissionToolCall`).
 */
data class ParsedPermission(
    val title: String,
    /**
     * The human-readable heading: `_meta.claudeCode.title` (claude-agent-acp ≥0.63), else the
     * `_meta.permission.title` the server hoists off the request (codex ≥1.7.0, claude ≥0.73.0).
     * A heading that merely restates [command] gives way to the model's own label for it
     * (claude-agent-acp 0.79.0 made the heading the raw command). Null: the card uses [title].
     */
    val description: String? = null,
    /** Why the agent needs this approval, in its own words (`_meta.permission.description`,
     *  version 1) — the sentence the decision rests on. */
    val reason: String? = null,
    /** `_meta.permission.defaultToNo` (claude-agent-acp ≥0.77.0): this ask must not be approvable
     *  by a stray tap, so the decline takes the emphasis. Only a literal `true` counts. */
    val defaultToNo: Boolean = false,
    /** The host a Claude Code sandbox asks to let a command reach (`SandboxNetworkAccess`), read
     *  only when the request names that tool in `_meta.claudeCode.toolName`. */
    val networkHost: String? = null,
    /** The tool call the request is about, when it names one. */
    val toolCallId: String? = null,
    val kind: String,
    val command: String?,
    val cwd: String?,
    val diffFiles: List<DiffFile>,
    val planMarkdown: String?,
    val planEntries: List<PermissionPlanEntry>,
    val allowedPrompts: List<PermissionAllowedPrompt>,
    val modeTarget: String?,
    val url: String?,
    val query: String?,
    val prompt: String?,
    val jsonPreview: String,
) {
    /** True for an ExitPlanMode / plan-bearing approval (drives plan-styled copy). */
    val isPlan: Boolean
        get() {
            val k = kind.lowercase().replace("_", "")
            return k.contains("plan") || planMarkdown != null || planEntries.isNotEmpty()
        }

    /** Whether the body has anything richer than the raw-JSON fallback. */
    val hasStructuredBody: Boolean
        get() = networkHost != null || command != null || diffFiles.isNotEmpty() || planMarkdown != null ||
            planEntries.isNotEmpty() || allowedPrompts.isNotEmpty() ||
            modeTarget != null || url != null || query != null || prompt != null

    companion object {
        fun parse(toolCall: JsonElement): ParsedPermission {
            val obj = toolCall as? JsonObject
            val rawKind = pickString(obj, listOf("kind", "toolName", "tool_name", "name", "type")) ?: "tool"

            val rawInputValue = pickValue(obj, listOf("rawInput", "raw_input", "input", "arguments", "params", "payload"))
            val rawInputObj = asObject(rawInputValue)

            val command = extractCommand(rawInputValue) ?: extractCommand(toolCall)
            val cwd = pickString(rawInputObj, listOf("cwd", "workdir", "workingDirectory", "working_directory"))
                ?: pickString(obj, listOf("cwd", "workdir", "workingDirectory", "working_directory"))

            val diffText = extractDiffText(rawInputValue, rawInputObj)
                ?: buildDiffFromChanges(rawInputObj, obj)
            val diffFiles = diffText?.let { UnifiedDiff.parse(it) } ?: emptyList()

            val planEntries = parsePlanEntries(rawInputObj)
            val planMarkdown = (pickValue(rawInputObj, listOf("plan")) as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

            val allowedPrompts = parseAllowedPrompts(rawInputObj)
            val modeTarget = pickString(rawInputObj, listOf("modeId", "mode_id", "targetMode", "target_mode"))
            val url = pickString(rawInputObj, listOf("url")) ?: pickString(obj, listOf("url"))
            val query = pickString(rawInputObj, listOf("query")) ?: pickString(obj, listOf("query"))
            val prompt = pickString(rawInputObj, listOf("prompt")) ?: pickString(obj, listOf("prompt"))
            val title = pickString(obj, listOf("title", "toolName", "tool_name", "name")) ?: fallbackTitle(rawKind)

            // NOTE the underscore: the request forwards the raw ACP tool call (serde renames
            // `meta` to `_meta`), unlike tool calls, whose event field is plain `meta`.
            val meta = asObject(pickValue(obj, listOf("_meta")))
            val claudeCode = asObject(pickValue(meta, listOf("claudeCode")))
            val permissionMeta = pickValue(meta, listOf("permission"))
            val metaHeading = pickString(claudeCode, listOf("title"))
                ?: pickString(asObject(permissionMeta), listOf("title"))
            val description = if (metaHeading != null && command != null && metaHeading == command) {
                shellCommandLabel(rawInputObj, command) ?: metaHeading
            } else {
                metaHeading
            }
            val networkHost = if (pickString(claudeCode, listOf("toolName")) == SANDBOX_NETWORK_TOOL) {
                pickString(rawInputObj, listOf("host"))
            } else {
                null
            }

            return ParsedPermission(
                title = title, description = description,
                reason = permissionMetaDescription(permissionMeta),
                defaultToNo = permissionMetaFlag(permissionMeta, "defaultToNo"),
                networkHost = networkHost,
                toolCallId = permissionToolCallId(toolCall),
                kind = rawKind, command = command, cwd = cwd, diffFiles = diffFiles,
                planMarkdown = planMarkdown, planEntries = planEntries, allowedPrompts = allowedPrompts,
                modeTarget = modeTarget, url = url, query = query, prompt = prompt,
                jsonPreview = prettyPrint(toolCall),
            )
        }
    }
}

private val PJson = Json { ignoreUnknownKeys = true; isLenient = true }
private val PrettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true; isLenient = true }

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.let { if (it.isString) it.content else null }
private fun JsonElement?.nonEmpty(): String? = str()?.takeIf { it.isNotEmpty() }
private fun JsonElement?.isNullElem(): Boolean = this == null || this is JsonNull
private fun parseJsonString(s: String): JsonElement? = runCatching { PJson.parseToJsonElement(s) }.getOrNull()

private fun asObject(v: JsonElement?): JsonObject? {
    if (v is JsonObject) return v
    if (v is JsonPrimitive && v.isString) {
        val t = v.content.trim()
        if (t.startsWith("{")) return parseJsonString(t) as? JsonObject
    }
    return null
}

private fun pickValue(obj: JsonObject?, keys: List<String>): JsonElement? {
    if (obj == null) return null
    for (k in keys) obj[k]?.takeIf { !it.isNullElem() }?.let { return it }
    return null
}

private fun pickString(obj: JsonObject?, keys: List<String>): String? {
    if (obj == null) return null
    for (k in keys) obj[k].nonEmpty()?.let { return it }
    return null
}

/** Claude Code's name for a sandbox's network request (see [ParsedPermission.networkHost]). */
private const val SANDBOX_NETWORK_TOOL = "SandboxNetworkAccess"

/** Longest agent-authored label promoted to the card's heading (the field is unbounded). */
private const val MAX_HEADING_CHARS = 200

/**
 * The model's own one-line label for a shell command (`input.description`), or null when it is
 * missing or only restates the command — so the heading isn't the command a second time.
 */
private fun shellCommandLabel(rawInputObj: JsonObject?, command: String): String? {
    val label = pickString(rawInputObj, listOf("description")) ?: return null
    return if (label == command) null else label.take(MAX_HEADING_CHARS)
}

/** The tool call a request names (web `extractPermissionToolCallId`). */
internal fun permissionToolCallId(toolCall: JsonElement?): String? {
    val obj = toolCall as? JsonObject ?: return null
    for (key in listOf("call_id", "callId", "tool_call_id", "toolCallId", "id")) {
        obj[key].str()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

/** The plain `description` of a version-1 `_meta.permission` block, within the card's budget. */
private fun permissionMetaDescription(permission: JsonElement?): String? {
    val record = asObject(permission) ?: return null
    if (!isPermissionMetaV1(record["version"])) return null
    return pickString(record, listOf("description"))?.take(MAX_PERMISSION_CHANGE_CHARS)
}

/** A flag of the same block, under the same version gate. Only a real `true` counts: these
 *  flags gate a SAFER presentation, so a shape this build doesn't understand reads as unset. */
private fun permissionMetaFlag(permission: JsonElement?, key: String): Boolean {
    val record = asObject(permission) ?: return false
    if (!isPermissionMetaV1(record["version"])) return false
    val flag = record[key] as? JsonPrimitive ?: return false
    return !flag.isString && flag.content == "true"
}

private fun fallbackTitle(kind: String): String {
    val normalized = kind.replace("_", " ").trim()
    if (normalized.isEmpty()) return ""
    return normalized.split(" ").filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}

/**
 * The command a request asks to run, dug out of whatever shape the agent used.
 *
 * [verbatim] marks a string leaf directly under a key that names a command (`command`, `cmd`,
 * `script`, `args`, …): such a string IS the command, so the two heuristics guarding the broad
 * walk don't apply to it — a `{`/`[` prefix (`[ -f x ] && make`, `{ npm test; }`) is shell
 * unless it parses as JSON, and a command that writes a patch isn't a diff payload. Either
 * would leave the card without its command block, the one thing a command approval must
 * show. The mark lasts one hop: a wrapper key below it reads conservatively again.
 */
private fun extractCommand(value: JsonElement?, depth: Int = 0, verbatim: Boolean = false): String? {
    if (depth > 4 || value == null || value.isNullElem()) return null
    when (value) {
        is JsonPrimitive -> {
            if (!value.isString) return null
            val t = value.content.trim()
            if (t.isEmpty()) return null
            if (!t.startsWith("{") && !t.startsWith("[")) {
                return if (!verbatim && UnifiedDiff.looksLikeDiff(t)) null else t
            }
            // A real envelope is read as before; one that doesn't parse is shell on a
            // command leaf, and an unreadable payload anywhere else.
            val parsed = parseJsonString(t) ?: return if (verbatim) t else null
            return extractCommand(parsed, depth + 1)
        }
        is JsonArray -> {
            val parts = value.mapNotNull { it.str() }.filter { it.trim().isNotEmpty() }
            val joined = parts.joinToString(" ").trim()
            return joined.ifEmpty { null }
        }
        is JsonObject -> {
            for (key in listOf("command", "cmd", "script", "args", "argv", "command_args", "commandArgs")) {
                extractCommand(value[key], depth + 1, verbatim = true)?.let { return it }
            }
            for (key in listOf("rawInput", "raw_input", "input", "arguments", "params", "payload")) {
                extractCommand(value[key], depth + 1)?.let { return it }
            }
            return null
        }
        else -> return null
    }
}

private fun extractDiffText(rawInput: JsonElement?, rawInputObj: JsonObject?): String? {
    val candidates = mutableListOf<JsonElement?>(rawInput)
    if (rawInputObj != null) {
        candidates.addAll(listOf(rawInputObj["patch"], rawInputObj["diff"], rawInputObj["unified_diff"], rawInputObj["unifiedDiff"]))
    }
    for (c in candidates) {
        val s = c.str() ?: continue
        val normalized = unescape(s).trim()
        if (normalized.isNotEmpty() && UnifiedDiff.looksLikeDiff(normalized)) return normalized
    }
    return null
}

private fun buildDiffFromChanges(rawInputObj: JsonObject?, toolCallObj: JsonObject?): String? {
    val blocks = mutableListOf<String>()

    if (rawInputObj != null) {
        val path = pickString(rawInputObj, listOf("file_path", "filePath", "path", "notebook_path", "notebookPath", "target_file", "targetFile"))
        if (path != null) {
            val oldText = pickString(rawInputObj, listOf("old_string", "oldString", "old_text", "oldText")) ?: ""
            val newText = pickString(rawInputObj, listOf("new_string", "newString", "new_text", "newText", "content", "text", "new_source", "newSource")) ?: ""
            compactDiff(path, oldText, newText)?.let { blocks.add(it) }
        }
    }

    (toolCallObj?.get("content") as? JsonArray)?.forEach { item ->
        val rec = item as? JsonObject ?: return@forEach
        if (rec["type"].str()?.lowercase() != "diff") return@forEach
        val path = rec["path"].nonEmpty() ?: return@forEach
        val oldText = (rec["old_text"] ?: rec["oldText"]).str() ?: ""
        val newText = (rec["new_text"] ?: rec["newText"]).str() ?: ""
        compactDiff(path, oldText, newText)?.let { blocks.add(it) }
    }

    val joined = blocks.joinToString("\n\n").trim()
    return joined.ifEmpty { null }
}

private fun compactDiff(path: String, oldText: String, newText: String, context: Int = 2): String? {
    val oldLines = oldText.split("\n").toMutableList()
    if (oldLines.lastOrNull() == "") oldLines.removeAt(oldLines.size - 1)
    val newLines = newText.split("\n").toMutableList()
    if (newLines.lastOrNull() == "") newLines.removeAt(newLines.size - 1)

    var prefix = 0
    while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
    var suffix = 0
    while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
        oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
    ) suffix++

    val removed = oldLines.subList(prefix, oldLines.size - suffix)
    val added = newLines.subList(prefix, newLines.size - suffix)
    if (removed.isEmpty() && added.isEmpty()) return null

    val before = oldLines.subList(maxOf(0, prefix - context), prefix)
    val after = oldLines.subList(oldLines.size - suffix, minOf(oldLines.size, oldLines.size - suffix + context))

    val oldStart = maxOf(1, prefix + 1 - before.size)
    val oldCount = before.size + removed.size + after.size
    val newCount = before.size + added.size + after.size

    val parts = mutableListOf("--- $path", "+++ $path", "@@ -$oldStart,$oldCount +$oldStart,$newCount @@")
    before.forEach { parts.add(" $it") }
    removed.forEach { parts.add("-$it") }
    added.forEach { parts.add("+$it") }
    after.forEach { parts.add(" $it") }
    return parts.joinToString("\n")
}

private fun parsePlanEntries(rawInputObj: JsonObject?): List<PermissionPlanEntry> {
    if (rawInputObj == null) return emptyList()
    for (key in listOf("plan", "entries", "steps", "todos")) {
        val list = rawInputObj[key] as? JsonArray ?: continue
        if (list.isEmpty()) continue
        val entries = list.mapNotNull { item ->
            val rec = item as? JsonObject ?: return@mapNotNull null
            val text = pickString(rec, listOf("step", "content", "title", "task", "description")) ?: return@mapNotNull null
            PermissionPlanEntry(text, pickString(rec, listOf("status", "state")))
        }
        if (entries.isNotEmpty()) return entries
    }
    return emptyList()
}

private fun parseAllowedPrompts(rawInputObj: JsonObject?): List<PermissionAllowedPrompt> {
    val list = pickValue(rawInputObj, listOf("allowedPrompts", "allowed_prompts")) as? JsonArray ?: return emptyList()
    return list.mapNotNull { item ->
        val rec = item as? JsonObject ?: return@mapNotNull null
        val prompt = pickString(rec, listOf("prompt", "description", "text")) ?: return@mapNotNull null
        PermissionAllowedPrompt(prompt, pickString(rec, listOf("tool", "toolName", "tool_name")) ?: "")
    }
}

/**
 * How long a permission change lasts, and where it is written — normalized from
 * the wire's `lifetime: {scope, storage?}` pair into one flat token the card can
 * label directly.
 *
 * [PERSISTENT] is the deliberate catch-all for a `scope: "persistent"` whose
 * `storage` this build does not recognize: the destination is unknown, but the
 * fact that the grant OUTLIVES the session is the part the user must not miss,
 * so it degrades to a vaguer warning rather than to silence.
 */
enum class PermissionChangeScope(@StringRes val labelRes: Int) {
    SESSION(R.string.perm_scope_session),
    PROCESS(R.string.perm_scope_process),
    USER(R.string.perm_scope_user),
    PROJECT(R.string.perm_scope_project),
    PROJECT_LOCAL(R.string.perm_scope_project_local),
    PERSISTENT(R.string.perm_scope_persistent),
}

/** One thing that picking a permission option would grant. */
data class PermissionOptionChange(
    /** The agent's own rendered sentence for this change. */
    val description: String,
    /**
     * `null` when the change carries no lifetime, reports `scope: "unknown"`, or
     * uses a scope this build does not know — the card then says nothing about
     * duration rather than guessing at one.
     */
    val scope: PermissionChangeScope?,
)

/**
 * Maximum change descriptions rendered for one option, and the per-description
 * character cap. The list is agent-authored and rides the broadcast event and
 * the snapshot, so a bound keeps a pathological (or simply very broad) grant
 * from turning the card into a wall of text. Anything past the cap is dropped
 * rather than summarized — the option's own name still states what it does.
 */
private const val MAX_PERMISSION_CHANGES = 6
private const val MAX_PERMISSION_CHANGE_CHARS = 200

/**
 * How many entries are examined at all, however many of them are kept. Bounding
 * only the output is not enough: an entry with no `description` is dropped and
 * the loop moves on, and [asObject] fully re-parses an entry that arrives as a
 * JSON-encoded *string* — the outer frame parse read those as opaque string
 * tokens, so that is new work, proportional to the payload, on the composition
 * thread. The slack over [MAX_PERMISSION_CHANGES] is for malformed entries
 * sitting ahead of good ones.
 */
private const val MAX_PERMISSION_CHANGES_SCANNED = 32

/**
 * What picking a permission option would change: the agent's own sentence for
 * each change, plus how long it lasts. Port of the web
 * `parsePermissionOptionChanges`.
 *
 * codex-acp ≥1.1.8 and claude-agent-acp ≥0.64.1 both hang
 * `_meta.permission = {version: 1, changes: [...]}` on a [PermissionOption],
 * where every change carries a rendered English sentence ("Allow access to
 * api.example.com for this session", "Allow all Bash calls"). Only `version: 1`
 * is read — a future revision may reshape `changes`, and showing it
 * half-understood is worse than showing nothing.
 *
 * `lifetime` is read because `description` alone does NOT always answer "for how
 * long": codex writes the duration into its sentences, claude does not — it
 * reports `{scope: "session"}` vs `{scope: "persistent", storage: "project"}`
 * structurally instead. Left unread, claude's most common card would pair an
 * "Always Allow" button with "Allow all Bash calls" and never reveal that the
 * grant expires with the session (or, worse, that it is about to be written into
 * settings the repo commits). The remaining structural fields (`targets`,
 * `ruleBehavior`) stay ignored: those `description` really does summarize.
 */
fun parsePermissionOptionChanges(meta: JsonElement?): List<PermissionOptionChange> {
    val permission = asObject(pickValue(asObject(meta), listOf("permission"))) ?: return emptyList()
    if (!isPermissionMetaV1(permission["version"])) return emptyList()
    // codex-acp ≥1.7.0 dropped `changes[]` for a flat `{version: 1, description}` (MCP
    // elicitation approvals): read as one change with no stated lifetime.
    val changes = permission["changes"] as? JsonArray
        ?: return permissionMetaDescription(permission)?.let { listOf(PermissionOptionChange(it, null)) } ?: emptyList()

    val out = mutableListOf<PermissionOptionChange>()
    for (change in changes.asSequence().take(MAX_PERMISSION_CHANGES_SCANNED)) {
        if (out.size >= MAX_PERMISSION_CHANGES) break
        val record = asObject(change)
        val description = pickTrimmed(record, "description") ?: continue
        out += PermissionOptionChange(
            description = description.take(MAX_PERMISSION_CHANGE_CHARS),
            scope = parseChangeScope(pickValue(record, listOf("lifetime"))),
        )
    }
    return out
}

/**
 * Whether `version` is the number 1. A JSON number is compared numerically, so
 * `1.0` and `1e0` pass exactly as they do in the web reference's `!== 1` (both
 * parse to the number 1 there) — but the *string* `"1"` does not: the field is
 * specified as a number, and a stringified one means some layer reshaped the
 * payload, which is the case this guard exists to catch.
 */
private fun isPermissionMetaV1(version: JsonElement?): Boolean {
    val primitive = version as? JsonPrimitive ?: return false
    return !primitive.isString && primitive.doubleOrNull == 1.0
}

/**
 * A trimmed, non-blank string field. The local [pickString] does not trim, but
 * the web reference does at every one of these sites — and an untrimmed
 * `storage` silently degrades " project " to the vaguer "Saved permanently".
 */
private fun pickTrimmed(obj: JsonObject?, key: String): String? =
    pickString(obj, listOf(key))?.trim()?.takeIf { it.isNotEmpty() }

/** Wire `lifetime` → [PermissionChangeScope]; see the enum's doc. */
private fun parseChangeScope(lifetime: JsonElement?): PermissionChangeScope? {
    val record = asObject(lifetime)
    return when (pickTrimmed(record, "scope")) {
        "session" -> PermissionChangeScope.SESSION
        "process" -> PermissionChangeScope.PROCESS
        "persistent" -> when (pickTrimmed(record, "storage")) {
            "user" -> PermissionChangeScope.USER
            "project" -> PermissionChangeScope.PROJECT
            "project_local" -> PermissionChangeScope.PROJECT_LOCAL
            else -> PermissionChangeScope.PERSISTENT
        }
        else -> null
    }
}

private fun unescape(text: String): String =
    text.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\t", "\t")

private fun prettyPrint(element: JsonElement): String =
    runCatching { PrettyJson.encodeToString(JsonElement.serializer(), element) }.getOrDefault(element.toString())
