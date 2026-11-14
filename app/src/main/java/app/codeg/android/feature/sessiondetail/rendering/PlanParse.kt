package app.codeg.android.feature.sessiondetail.rendering

import app.codeg.android.core.model.PlanEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A plan checklist read off a tool call (codeg web 0.44 `plan-parse.ts`), so a persisted
 * `TodoWrite` / `update_plan` call draws as the same plan card the live stream's plan update
 * does, rather than as a tool card.
 */
object PlanParse {

    /** The name of the call a turn handed off locally keeps its live plan as. */
    const val HANDOFF_TOOL_NAME = "TodoWrite"

    /**
     * Whether a call by [name] may carry a plan (web `isPlanLikeToolName`): `TodoWrite`, Kimi's
     * `TodoList`, or anything with "plan" in it (codex's `update_plan`). Plan-mode transitions
     * match too, harmlessly: their input holds no entries, and only a call that yields some
     * converts.
     */
    fun isPlanLikeToolName(name: String): Boolean {
        val n = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        return n == "todowrite" || n == "todolist" || "plan" in n
    }

    /** The checklist in a call's input: its `todos`, `entries` or `plan` array (web `parseTodosFromJson`). */
    fun parseTodos(input: String?): List<PlanEntry> {
        if (input.isNullOrBlank()) return emptyList()
        val obj = runCatching { Json.parseToJsonElement(input) as? JsonObject }.getOrNull() ?: return emptyList()
        for (key in listOf("todos", "entries", "plan")) {
            val list = obj[key] as? JsonArray ?: continue
            val entries = list.mapNotNull(::entryOf)
            if (entries.isNotEmpty()) return entries
        }
        return emptyList()
    }

    private fun entryOf(item: Any?): PlanEntry? {
        val record = item as? JsonObject ?: return null
        fun str(key: String) = (record[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val content = (str("content") ?: str("step") ?: str("title") ?: str("name") ?: "").trim()
        if (content.isEmpty()) return null
        return PlanEntry(content = content, priority = normalizePriority(str("priority")), status = normalizeStatus(str("status")))
    }

    private fun normalizeStatus(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "completed", "done" -> "completed"
        "in_progress", "in-progress", "in progress", "running", "active" -> "in_progress"
        else -> "pending"
    }

    private fun normalizePriority(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "high", "urgent" -> "high"
        "low" -> "low"
        else -> "medium"
    }

    /** [entries] as the input of a [HANDOFF_TOOL_NAME] call, which [parseTodos] reads back. */
    fun handoffInput(entries: List<PlanEntry>): String = buildJsonObject {
        put(
            "todos",
            buildJsonArray {
                entries.forEach { e ->
                    add(
                        buildJsonObject {
                            put("content", e.content)
                            put("status", e.status)
                            put("priority", e.priority)
                        },
                    )
                }
            },
        )
    }.toString()
}
