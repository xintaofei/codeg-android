package app.codeg.android.feature.sessiondetail.interactive

import app.codeg.android.feature.sessiondetail.LiveToolCallState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A permission request's `tool_call` and the live call it is about, each filling what the other
 * left out (codeg web 0.44 `mergePermissionToolCallWithLiveInfo`, in the `PERMISSION_REQUEST`
 * reducer). The request is the authority on WHAT is being approved; the live call only lends
 * fields the request never sent.
 */
object PermissionMerge {

    private val INPUT_KEYS = listOf("rawInput", "raw_input", "input", "arguments", "params", "payload")

    /** The keys carrying an edit's text in a file tool's input, in every spelling the card reads. */
    private val EDIT_TEXT_KEYS = listOf(
        "old_string", "oldString", "old_text", "oldText", "new_string", "newString", "new_text", "newText",
        "content", "text", "new_source", "changes", "diff", "patch", "unified_diff", "unifiedDiff",
    )

    /** The file a file tool's input names, in any spelling the card reads. */
    private val EDIT_PATH_KEYS = listOf("file_path", "filePath", "path", "notebook_path", "target_file", "targetFile")

    private val WRAPPER_KEYS = setOf("content", "kind", "status", "title", "toolCallId", "tool_call_id", "callId", "call_id") + INPUT_KEYS

    private val json = Json

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun inputValue(record: JsonObject): Pair<String, JsonElement>? {
        for (key in INPUT_KEYS) {
            val value = record[key] ?: continue
            if (value is JsonNull) continue
            if (value is JsonPrimitive && value.isString && value.content.isBlank()) continue
            return key to value
        }
        return null
    }

    private fun serialize(value: JsonElement?): String? = when (value) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content.takeIf { it.isNotBlank() } else value.toString()
        else -> value.toString()
    }

    /**
     * The input a request carries, as a tool call's raw input: its nested input when it has
     * one, else the request with its wrapper fields stripped (web `serializePermissionToolCall`).
     */
    fun requestInput(toolCall: JsonElement): String? {
        val record = toolCall as? JsonObject ?: return null
        inputValue(record)?.let { (_, value) -> serialize(value)?.let { return it } }
        val rest = JsonObject(record.filterKeys { it !in WRAPPER_KEYS })
        return if (rest.isEmpty()) null else rest.toString()
    }

    /** The title a request names its call by. */
    fun requestTitle(toolCall: JsonElement): String? = pick(toolCall, "title", "tool_name", "name", "type")

    /** The kind a request names its call by. */
    fun requestKind(toolCall: JsonElement): String? = pick(toolCall, "kind", "tool_name", "name", "type")

    private fun pick(toolCall: JsonElement, vararg keys: String): String? {
        val record = toolCall as? JsonObject ?: return null
        return keys.firstNotNullOfOrNull { record[it].str()?.takeIf { s -> s.isNotBlank() } }
    }

    private fun record(value: JsonElement?): JsonObject? = when (value) {
        is JsonObject -> value
        is JsonPrimitive -> if (value.isString) runCatching { json.parseToJsonElement(value.content) as? JsonObject }.getOrNull() else null
        else -> null
    }

    private fun filePath(input: JsonObject): String? =
        EDIT_PATH_KEYS.firstNotNullOfOrNull { input[it].str()?.takeIf { s -> s.isNotBlank() } }

    /**
     * A file tool's request input with the edit text put back from the live call, or null when
     * nothing is missing. claude-agent-acp 0.82.0 sends an approval whose input is `{file_path}`
     * alone — the text lives only in the live call. Only an input with NONE of the text keys is
     * filled, only from a live input naming the same file, and the request's own keys win.
     */
    internal fun fillStrippedEditInput(requestInput: JsonElement?, liveRawInput: String?): JsonObject? {
        val request = record(requestInput) ?: return null
        val live = record(liveRawInput?.let { JsonPrimitive(it) }) ?: return null
        if (EDIT_TEXT_KEYS.any { it in request }) return null
        if (EDIT_TEXT_KEYS.none { it in live }) return null
        val path = filePath(request) ?: return null
        if (path != filePath(live)) return null
        return JsonObject(live + request)
    }

    /** [toolCall] with what [live] knows and it doesn't; [toolCall] itself when nothing applies. */
    fun merge(toolCall: JsonElement, live: LiveToolCallState?): JsonElement {
        if (live == null) return toolCall
        val rawInput = live.rawInput?.takeIf { it.isNotBlank() }
        val record = toolCall as? JsonObject
            ?: return if (rawInput == null) {
                toolCall
            } else {
                buildJsonObject {
                    put("toolCallId", JsonPrimitive(live.id))
                    put("title", JsonPrimitive(live.title))
                    put("kind", JsonPrimitive(live.kind))
                    put("rawInput", JsonPrimitive(rawInput))
                }
            }
        val next = record.toMutableMap()
        var changed = false
        val existing = inputValue(record)
        if (existing == null || serialize(existing.second) == null) {
            if (rawInput != null) {
                next["rawInput"] = JsonPrimitive(rawInput)
                changed = true
            }
        } else {
            fillStrippedEditInput(existing.second, live.rawInput)?.let {
                next[existing.first] = it
                changed = true
            }
        }
        if (record["title"].str().isNullOrBlank() && live.title.isNotBlank()) {
            next["title"] = JsonPrimitive(live.title)
            changed = true
        }
        if (record["kind"].str().isNullOrBlank() && live.kind.isNotBlank()) {
            next["kind"] = JsonPrimitive(live.kind)
            changed = true
        }
        if (permissionToolCallId(record) == null) {
            next["toolCallId"] = JsonPrimitive(live.id)
            changed = true
        }
        // Which tool the request is about, by Claude Code's own name: the request names none,
        // only the live call does, and the card needs it to recognise a sandbox's network ask.
        val liveToolName = ((live.meta?.get("claudeCode") as? JsonObject)?.get("toolName")).str()?.trim()?.takeIf { it.isNotEmpty() }
        if (liveToolName != null) {
            val meta = record["_meta"] as? JsonObject
            val claudeCode = meta?.get("claudeCode") as? JsonObject
            if (claudeCode?.get("toolName").str() == null) {
                next["_meta"] = JsonObject(
                    (meta ?: JsonObject(emptyMap())) +
                        ("claudeCode" to JsonObject((claudeCode ?: JsonObject(emptyMap())) + ("toolName" to JsonPrimitive(liveToolName)))),
                )
                changed = true
            }
        }
        return if (changed) JsonObject(next) else toolCall
    }
}
