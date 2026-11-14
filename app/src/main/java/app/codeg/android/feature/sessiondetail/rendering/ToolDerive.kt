package app.codeg.android.feature.sessiondetail.rendering

import app.codeg.android.core.designsystem.diff.DiffFile
import app.codeg.android.core.designsystem.diff.DiffHunk
import app.codeg.android.core.designsystem.diff.DiffRow
import app.codeg.android.core.designsystem.diff.UnifiedDiff
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Coarse category a tool falls into — drives the icon and group summary. */
enum class ToolKindBucket { READ, EDIT, SEARCH, EXECUTE, WEB, TODO, TASK, OTHER }

/**
 * Pure helpers that turn a tool name + parsed input into a human title, a bucket,
 * and (for edits) a diff. Faithful port of the iOS `ToolDerive` (which mirrors the
 * codeg client's `deriveToolTitle` / tool-kind classifier).
 */
object ToolDerive {

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    // Canonical tool-name sets — must mirror the iOS `ToolDerive` byte-for-byte so
    // bucketing (which drives grouping, icons, and titles) matches the reference.
    private val readNames = setOf("read", "read_file", "readfile", "read_text_file", "cat", "view")
    private val editNames = setOf(
        "edit", "str_replace", "str_replace_editor", "str_replace_based_edit_tool",
        "apply_patch", "applypatch", "patch", "multiedit", "edit_file", "update_file",
        "write", "write_file", "writefile", "create_file", "notebookedit", "notebook_edit",
        "write_to_file", "replace_in_file",
    )
    private val searchNames = setOf(
        "grep", "ripgrep", "grep_search", "search_files", "search", "searchtext", "search_text",
        "glob", "find", "file_search", "ls", "list_dir", "list_files", "list_code_definition_names",
    )
    private val executeNames = setOf(
        "bash", "shell", "sh", "exec", "exec_command", "execute", "execute_command",
        "run_command", "command", "run_terminal_cmd", "terminal",
    )
    private val webNames = setOf(
        "webfetch", "web_fetch", "fetch", "websearch", "web_search", "browser", "browser_action",
    )
    private val todoNames = setOf("todowrite", "todo_write", "update_todo_list", "todos")
    private val taskNames = setOf(
        "task", "taskcreate", "taskupdate", "tasklist", "agent", "dispatch_agent", "new_task",
        "delegate_to_agent", "delegate_task", "subagent", "spawn_agent", "call_omo_agent",
    )

    fun parseJson(s: String?): JsonObject? {
        if (s.isNullOrBlank()) return null
        return runCatching { lenient.parseToJsonElement(s).jsonObject }.getOrNull()
    }

    /** Descend into a wrapper object (`input`/`arguments`/…) when args were nested. */
    fun effectiveArgs(parsed: JsonObject?): JsonObject? {
        if (parsed == null) return null
        for (key in listOf("input", "arguments", "params", "payload")) {
            (parsed[key] as? JsonObject)?.let { return it }
        }
        return parsed
    }

    fun title(name: String, parsed: JsonObject?): String {
        val n = canonical(name)
        val args = effectiveArgs(parsed)
        fun str(keys: List<String>): String? {
            for (k in keys) args.string(k)?.takeIf { it.isNotEmpty() }?.let { return it }
            return null
        }

        // Plan-mode transitions get a fixed human title. Grok's own titles mutate as
        // the call progresses, so deriving from the (already canonicalized) name keeps
        // the card stable across the lifecycle.
        if (isPlanModeName(name)) {
            return when (n.replace("_", "")) {
                "enterplanmode" -> "Entered plan mode"
                "exitplanmode" -> "Plan ready"
                else -> "Switched mode"
            }
        }

        // Codex code-mode identities. Titled from the fields the server's parser
        // recovered rather than from the arguments, which hold a JS program or a
        // bare session id — the generic rules produce "wait: 7106" for those.
        if (CodexCodeMode.isScriptName(name)) return scriptTitle(args)
        if (CodexCodeMode.isShellSessionName(name)) return sessionTitle(name.trim().lowercase(), args)

        return when (n) {
            "read", "read_file", "readfile", "cat", "view" ->
                str(listOf("file_path", "path", "filename", "file", "target_file"))?.let { "Read ${PathFormat.short(it)}" }
                    ?: display(name)
            "edit", "str_replace", "str_replace_editor", "str_replace_based_edit_tool",
            "apply_patch", "applypatch", "patch", "multiedit", "edit_file" -> {
                val count = editFileCount(args)
                if (count != null && count > 1) "Edit ($count files)"
                else str(listOf("file_path", "path", "filename", "file", "target_file"))?.let { "Edit ${PathFormat.short(it)}" } ?: "Edit"
            }
            "write", "write_file", "writefile", "create_file", "notebookedit", "notebook_edit" ->
                str(listOf("file_path", "path", "filename", "file", "target_file"))?.let { "Write ${PathFormat.short(it)}" } ?: "Write"
            "bash", "shell", "exec", "exec_command", "execute", "run_command", "command", "run_terminal_cmd", "terminal" ->
                str(listOf("description")) ?: str(listOf("command", "cmd", "script"))?.let { simplifyCommand(it) } ?: "Bash"
            "grep", "ripgrep", "grep_search", "search_files", "search" ->
                str(listOf("pattern", "query", "regex", "q"))?.let { "Grep ${truncate(it, 50)}" } ?: "Grep"
            "glob", "find", "file_search" ->
                str(listOf("pattern", "glob", "path", "query"))?.let { "Glob ${truncate(it, 50)}" } ?: "Glob"
            "ls", "list_dir", "list_files" ->
                str(listOf("path", "dir", "directory"))?.let { "List ${PathFormat.short(it)}" } ?: "List"
            "webfetch", "web_fetch", "fetch" ->
                str(listOf("url"))?.let { "WebFetch ${host(it)}" } ?: "WebFetch"
            "websearch", "web_search" ->
                str(listOf("query", "q"))?.let { "WebSearch: ${truncate(it, 50)}" } ?: "WebSearch"
            "todowrite", "todo_write", "update_todo_list", "todos" ->
                todoCounts(args)?.let { (done, total) -> "Todos ($done/$total)" } ?: "Todos"
            "task", "taskcreate", "agent", "dispatch_agent", "new_task", "delegate_to_agent" ->
                str(listOf("subagent_type", "agent_type", "description", "subject"))?.let { "${display(name)}: ${truncate(it, 50)}" }
                    ?: display(name)
            "skill" -> str(listOf("name", "skill", "command"))?.let { "Skill: $it" } ?: "Skill"
            else -> firstStringValue(args)?.let { "${display(name)}: ${truncate(it, 50)}" } ?: display(name)
        }
    }

    /** "Script" / the first command the parser could read, with the call count beside it. */
    private fun scriptTitle(args: JsonObject?): String {
        val script = CodexCodeMode.parseScript(args) ?: return "Script"
        script.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return simplifyCommand(it) }
        if (script.callCount > 0) return "Script (${script.callCount} tool calls)"
        return "Script"
    }

    /**
     * "Wait <command>" / "Terminate <command>" / "Stdin ^C", falling back to the
     * bare session id when the history parser could not resolve one — that id is
     * also shown beside the command that started the session, so the two can be
     * matched up by eye.
     */
    private fun sessionTitle(name: String, args: JsonObject?): String {
        val call = CodexCodeMode.parseSessionCall(args) ?: return display(name)
        if (name == CodexCodeMode.WRITE_STDIN_TOOL_NAME && call.chars.isNotEmpty()) {
            return "Stdin ${CodexCodeMode.describeStdinChars(call.chars)}"
        }
        val verb = if (call.terminate) "Terminate" else "Wait"
        call.command?.let { return "$verb ${simplifyCommand(it)}" }
        call.sessionId?.let { return "$verb cell $it" }
        return display(name)
    }

    /**
     * Coarse category for a tool. The ACP `kind` hint (live tools carry an
     * authoritative category) wins first; otherwise we match on **exact**
     * canonical-name set membership — substring matching mis-bucketed MCP tools
     * (e.g. `mcp__db__run_query`). Port of the iOS `ToolDerive.bucket`.
     */
    fun bucket(name: String, kind: String): ToolKindBucket {
        when (kind.lowercase()) {
            "read" -> return ToolKindBucket.READ
            "edit", "write", "delete", "move" -> return ToolKindBucket.EDIT
            "search" -> return ToolKindBucket.SEARCH
            "execute", "command", "bash", "shell", "terminal" -> return ToolKindBucket.EXECUTE
            "fetch", "web" -> return ToolKindBucket.WEB
        }
        // Ahead of the canonical strip, though still behind the `kind` hint:
        // codex's session tools continue a background shell an `exec_command`
        // started, so they belong with the commands in a tool group's tally,
        // and the code-mode script card is a program codex ran. Both are
        // matched exactly — see [CodexCodeMode.isShellSessionName] for why an
        // `mcp__…__wait` must not land here.
        if (CodexCodeMode.isShellSessionName(name) || CodexCodeMode.isScriptName(name)) {
            return ToolKindBucket.EXECUTE
        }
        val n = canonical(name)
        if (n in readNames) return ToolKindBucket.READ
        if (n in todoNames) return ToolKindBucket.TODO
        if (n in taskNames) return ToolKindBucket.TASK
        if (n in editNames) return ToolKindBucket.EDIT
        if (n in searchNames) return ToolKindBucket.SEARCH
        if (n in executeNames) return ToolKindBucket.EXECUTE
        if (n in webNames) return ToolKindBucket.WEB
        return ToolKindBucket.OTHER
    }

    fun diff(
        bucket: ToolKindBucket,
        input: String?,
        output: String?,
        content: String?,
        parsed: JsonObject?,
    ): List<DiffFile>? {
        if (bucket != ToolKindBucket.EDIT) return null
        for (candidate in listOfNotNull(content, output, input)) {
            if (UnifiedDiff.looksLikeDiff(candidate)) {
                UnifiedDiff.parse(candidate)?.let { return it }
            }
        }
        val args = effectiveArgs(parsed)
        if (args != null) {
            val oldS = args.string("old_string") ?: args.string("old_str")
            val newS = args.string("new_string") ?: args.string("new_str")
            if (oldS != null && newS != null) {
                val path = args.string("file_path") ?: args.string("path") ?: "edit"
                return synthesizeDiff(path, oldS, newS)
            }
        }
        return null
    }

    /**
     * Grok stamps the authoritative tool identity in `_meta["x.ai/tool"]`
     * (`{name, kind, namespace, label}`). For its plan-mode tools this returns the
     * canonical `enter_plan_mode` / `exit_plan_mode`; null for every other Grok tool
     * and every non-Grok host, so their existing name resolution is preserved.
     *
     * Keyed on the stable `kind` discriminator, which — unlike `title`, the only name
     * the live path is handed — does NOT mutate across the tool-call lifecycle
     * (`enter_plan_mode` → "Plan: Enter" → "Plan mode entered").
     */
    fun grokPlanModeName(meta: JsonObject?): String? =
        when ((meta?.get("x.ai/tool") as? JsonObject)?.string("kind")) {
            "enter_plan" -> "enter_plan_mode"
            "exit_plan" -> "exit_plan_mode"
            else -> null
        }

    /**
     * Plan-*mode* transition tools (Claude's `EnterPlanMode`/`ExitPlanMode`, Grok's
     * `enter_plan_mode`/`exit_plan_mode`, Cline's `switch_mode`). Mirrors the web
     * `isPlanModeToolName`: deliberately NOT the looser "contains plan" test, so
     * Codex's `update_plan` (a real checklist) keeps its own rendering.
     */
    fun isPlanModeName(name: String): Boolean =
        canonical(name).replace("_", "") in planModeNames

    private val planModeNames = setOf("enterplanmode", "exitplanmode", "switchmode")

    private fun canonical(name: String): String {
        var n = name.lowercase()
        val idx = n.lastIndexOf("__")
        if (idx >= 0) n = n.substring(idx + 2)
        return n
    }

    fun display(name: String): String {
        var n = name
        val idx = n.lastIndexOf("__")
        if (idx >= 0) n = n.substring(idx + 2)
        return n
    }

    private fun editFileCount(args: JsonObject?): Int? {
        (args?.get("changes") as? JsonArray)?.let { return it.size }
        (args?.get("edits") as? JsonArray)?.let { return it.size }
        val patch = args.string("patch") ?: args.string("input")
        if (patch != null && patch.contains("*** ")) {
            val n = patch.split("\n").count {
                val t = it.trim()
                t.startsWith("*** Add File:") || t.startsWith("*** Update File:") || t.startsWith("*** Delete File:")
            }
            if (n > 0) return n
        }
        return null
    }

    private fun todoCounts(args: JsonObject?): Pair<Int, Int>? {
        val todos = (args?.get("todos") as? JsonArray) ?: (args?.get("todoList") as? JsonArray) ?: return null
        val total = todos.size
        val done = todos.count { (it as? JsonObject).string("status") == "completed" }
        return done to total
    }

    private fun firstStringValue(args: JsonObject?): String? {
        if (args == null) return null
        val skip = setOf("id", "tool_use_id", "tool_call_id", "type")
        for (k in args.keys.sorted()) {
            if (k.lowercase() in skip) continue
            args.string(k)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private val shellPrefix = Regex("""^(?:/usr/bin/)?(?:ba)?sh\s+-l?c\s+""")

    private fun simplifyCommand(c: String): String {
        var cmd = c.trim()
        shellPrefix.find(cmd)?.let { m ->
            cmd = cmd.removeRange(m.range).trim().trim('\'', '"')
        }
        val firstLine = cmd.split("\n").firstOrNull() ?: cmd
        return truncate(firstLine, 80)
    }

    private fun host(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: truncate(url, 40)

    private fun truncate(s: String, n: Int): String {
        val t = s.trim()
        return if (t.length > n) t.take(n) + "…" else t
    }

    private fun synthesizeDiff(path: String, old: String, new: String): List<DiffFile> {
        val rows = mutableListOf<DiffRow>()
        for (l in old.split("\n")) rows.add(DiffRow(DiffRow.Kind.DELETED, null, null, l))
        for (l in new.split("\n")) rows.add(DiffRow(DiffRow.Kind.ADDED, null, null, l))
        val dels = old.split("\n").size
        val adds = new.split("\n").size
        return listOf(
            DiffFile(path, null, DiffFile.Mode.MODIFIED, adds, dels, listOf(DiffHunk(null, rows))),
        )
    }
}

/** Shorten a file path to its last two segments ("src/foo.ts"). */
object PathFormat {
    fun short(path: String): String {
        val comps = path.trim().split("/").filter { it.isNotEmpty() }
        return if (comps.size <= 2) comps.joinToString("/") else comps.takeLast(2).joinToString("/")
    }
}

/** Read a string field from a (possibly null) JsonObject; null if absent/non-string. */
private fun JsonObject?.string(key: String): String? {
    val prim = this?.get(key) as? JsonPrimitive ?: return null
    return if (prim.isString) prim.content else null
}
