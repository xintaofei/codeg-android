package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a LIVE tool call is, when its title doesn't say — port of the identity half of the
 * web's `inferLiveToolName` (`tool-call-normalization.ts`).
 *
 * A persisted call carries the tool's own name; a live one only a human title, which for
 * many tools names nothing: Claude Code titles an Agent/Task launch by its description
 * ("Explore the codebase", kind `think`), Qoder an MCP call by a sentence. Classified by
 * that title the launch is not a sub-agent — it drops out of the running sub-agents, isn't
 * kept in view when its reply folds, and turns into one only when the transcript is read
 * back. What the wire does state is ported here: the agents' own `_meta` name stamps, the
 * sub-agent markers, the delegation broker's marker, and the input shapes.
 *
 * Returns null where none of those speak, and the title stands, as before. The web's
 * title and kind fallbacks (its `normalizeToolName`) are not ported: [ToolDerive.bucket]
 * already reads the title through its own canonical sets, and only that coarse category is
 * decided from this name ([ToolCallVM.of]). Not ported either: the codex collab card and
 * codex plan review, which have no counterpart here.
 */
internal object LiveToolName {

    // The codeg-mcp delegation companions: their identity must win over the input-shape
    // heuristics (`cancel_delegation`'s `{task_id}` reads as the generic "task" tool).
    private val DELEGATION_COMPANION = Regex(
        "(?:^|[^a-z0-9])(delegate_to_agent|get_delegation_status|cancel_delegation|resume_delegation)$",
    )

    // Built-ins whose meta name outranks their input shape, which names another tool:
    // Claude Code's `Monitor` takes a `command`, its `SandboxNetworkAccess` a `{host}`.
    private val META_NAMED = setOf("monitor", "sandboxnetworkaccess")

    fun infer(title: String?, kind: String?, rawInput: String?, meta: JsonObject?): String? {
        // The backend's own sub-agent sentinel (OpenCode), by literal equality: a title that
        // merely contains the word ("Inspect agent config") is not one.
        if (title?.trim()?.lowercase() == "agent") return "agent"

        ToolDerive.grokPlanModeName(meta)?.let { return it }

        val claudeName = metaName(meta, "claudeCode", "toolName")
        val qoderName = metaName(meta, "qoder", "toolName")
        for (candidate in listOfNotNull(claudeName, qoderName)) {
            companion(candidate)?.let { return it }
            candidate.lowercase().takeIf { it in META_NAMED }?.let { return it }
        }

        // The broker's marker on the parent's `delegate_to_agent` call — the only live
        // identity on hosts whose wire loses the MCP name (Cursor's "MCP: tool").
        if (meta?.containsKey("codeg.delegation") == true) return "delegate_to_agent"

        // Grok unwraps its `use_tool` envelope, so the title is the raw `<server>__<tool>`.
        companion(title.orEmpty())?.let { return it }

        // claude-agent-acp ≥0.63 marks an Agent/Task launch, from its first frame on.
        if (claudeCodeMarksSubagent(meta)) return "agent"

        // The names the backend lifted off the one frame that states them (OpenCode, Kiro):
        // their input shapes are ambiguous, so these come before them.
        metaName(meta, "opencode", "toolName")?.let { return it }
        metaName(meta, "kiro", "canonicalToolName")?.let { return it }

        fromInput(rawInput, kind, title)?.let { return it }

        // Below the input shape, so Claude Code's `Task` keeps routing by `subagent_type`.
        claudeName?.let { return it.lowercase() }
        grokName(meta)?.let { return it }
        qoderName?.let { return it.lowercase() }
        return null
    }

    /** claude-agent-acp's `_meta.claudeCode.subagent`, strictly `true`. */
    fun claudeCodeMarksSubagent(meta: JsonObject?): Boolean =
        ((meta?.get("claudeCode") as? JsonObject)?.get("subagent") as? JsonPrimitive)
            ?.let { !it.isString && it.content == "true" } == true

    private fun companion(name: String): String? =
        DELEGATION_COMPANION.find(name.trim().lowercase())?.groupValues?.get(1)

    private fun metaName(meta: JsonObject?, namespace: String, field: String): String? =
        ((meta?.get(namespace) as? JsonObject)?.get(field) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** Grok's `_meta["x.ai/tool"].name`, except its generic `use_tool` MCP envelope. */
    private fun grokName(meta: JsonObject?): String? =
        metaName(meta, "x.ai/tool", "name")?.takeIf { it != "use_tool" }

    /** The web's `inferFromInput`: what the arguments' shape says the tool is. */
    internal fun fromInput(rawInput: String?, kind: String?, title: String?): String? {
        if (rawInput.isNullOrEmpty()) return null
        val k = simpleName(kind)
        val t = simpleName(title)
        if (rawInput.contains("*** Begin Patch")) return "apply_patch"
        val trimmed = rawInput.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("{") && !trimmed.startsWith("[") &&
            (k == "bash" || k == "exec_command" || k == "tool" || t == "bash" || t == "exec_command")
        ) {
            return "bash"
        }
        val parsed = ToolDerive.parseJson(rawInput) ?: return null
        fun has(vararg keys: String) = keys.any { key -> parsed[key].let { it != null && it !is JsonNull } }
        fun str(key: String) = (parsed[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        // Cursor's live MCP call names its provider and tool in the input.
        val provider = str("providerIdentifier")
        val tool = str("toolName")
        if (provider != null && !tool.isNullOrEmpty()) return if (provider.isNotEmpty()) "${provider}__$tool" else tool

        if (has("command", "cmd", "script", "args", "argv", "command_args", "command_line", "CommandLine")) return "bash"
        if (has("old_string", "new_string", "replace_all", "oldString", "newString", "replaceAll")) return "edit"
        if (has("changes")) return "edit"
        if (has("todos")) return "todowrite"
        // `query` is common to MCP tools; only a call the wire also names a web search is one.
        if (has("query") && (t == "websearch" || k == "websearch" || isWebSearchTitle(title) || isWebSearchType(parsed["type"]))) {
            return "websearch"
        }
        if (has("url")) return "webfetch"
        val hasPattern = has("pattern")
        val hasGlob = has("glob")
        if (hasPattern) return if (hasGlob) "glob" else "grep"
        if (hasGlob) return "glob"
        if (has("question", "questions")) return "question"
        // Claude Code's Task shape, and Cursor's.
        if (has("subagent_type", "subagentType")) return "agent"
        if (has("taskId", "task_id", "subject")) return "task"
        // Cursor stamps the tool in `_toolName` before its arguments stream in.
        str("_toolName")?.takeIf { it.isNotEmpty() }?.let { hinted ->
            if (hinted == "task") return "agent"
            return hinted.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
        }
        if (has("file_path", "notebook_path", "path", "filePath")) {
            if (has("content", "new_source", "cell_type", "edit_mode")) return "write"
            if (k in setOf("read", "edit", "write", "delete", "move")) return k
            if (t in setOf("read", "edit", "write")) return t
            return "read"
        }
        return null
    }

    /**
     * The few readings of a kind or title [fromInput] compares against — the web runs both
     * through its whole `normalizeToolName` for that; these are the outcomes it can reach.
     * A title is read by its first word ("Read src/a.kt" is a read), as its freeform
     * `^read` / `^edit` / `^write` rules do.
     */
    private fun simpleName(raw: String?): String {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return "tool"
        return when (s) {
            "bash", "shell", "sh", "execute", "exec", "terminal", "powershell", "shell_command",
            "run_command", "execute_command", "run_terminal_command" -> "bash"
            "exec_command" -> "exec_command"
            "websearch", "web_search" -> "websearch"
            else -> when (s.substringBefore(' ')) {
                "read", "read_file", "readfile", "read_text_file" -> "read"
                "edit", "edit_file", "update_file", "change", "changes" -> "edit"
                "write", "write_file", "writefile", "write_to_file" -> "write"
                "delete" -> "delete"
                "move" -> "move"
                else -> s
            }
        }
    }

    private val WEB_SEARCH_TITLE = Regex("^(?:web\\s+search|open\\s+page|find\\s+in\\s+page)(?:\\s*:|\\s|$)", RegexOption.IGNORE_CASE)
    private val GEMINI_WEB_SEARCH_TITLE = Regex("^searching\\s+the\\s+web\\s+for\\s*:", RegexOption.IGNORE_CASE)

    /** Codex's web-search follow-ups and Gemini's web search, both sent as kind `search`. */
    private fun isWebSearchTitle(title: String?): Boolean {
        val s = title?.trim().orEmpty()
        return s.isNotEmpty() && (WEB_SEARCH_TITLE.containsMatchIn(s) || GEMINI_WEB_SEARCH_TITLE.containsMatchIn(s))
    }

    /** Codex's raw-input marker, camelCase on the wire (`webSearch`). */
    private fun isWebSearchType(type: JsonElement?): Boolean {
        val s = (type as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return s.lowercase().replace("_", "").replace("-", "") == "websearch"
    }
}
