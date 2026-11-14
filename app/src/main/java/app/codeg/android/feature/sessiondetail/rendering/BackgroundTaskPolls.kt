package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Background tasks a command left running, and the calls that later poll them: port of the
 * web's `src/lib/background-task.ts` (codeg 0.44).
 *
 * Claude Code starts a background shell with `Bash(run_in_background: true)`, whose result is a
 * launch line carrying the task id ("Command running in background with ID: <id>. …"). The agent
 * then polls with `TaskOutput({task_id, block, timeout})`, whose result is an XML-tagged
 * envelope, and may finally `TaskStop({task_id})`, whose result is a JSON object carrying the
 * original command:
 *
 *     poll → <retrieval_status>success|timeout|not_ready</retrieval_status>
 *            <task_id>…</task_id> <task_type>local_bash</task_type>
 *            <status>running|completed</status> <exit_code>0</exit_code>
 *            <output>…(ANSI shell output)…</output>
 *     stop → {"message":"Successfully stopped task: … (<command>)", "task_id":…, "command":…}
 *
 * Grok reports the same lifecycle as a JSON envelope (`{"type":"TaskOutput","Result":{…}}`,
 * also `MultiResult`, and `SubagentCompleted` for a polled sub-agent), CodeBuddy as a plain-text
 * snapshot (`Task ID: …` / `Shell ID: …`). The same task is polled again and again, so a run of
 * polls draws as one lane: a row per task, its latest poll speaking for it.
 *
 * Not to be confused with [BackgroundTask], an async SUB-AGENT's launch card lifecycle.
 *
 * ADAPTATION: a [ToolCallVM] has no separate `errorText` channel: an error result rides in
 * [ToolCallVM.output] with [ToolCallVM.isError] set. The web's "unsettled" call (a live state,
 * or a forwarded ACP status that is not terminal) is a RUNNING / INPUT_STREAMING state here.
 */
object BackgroundTaskPolls {

    /** One task as one tool result reported it. */
    data class Envelope(
        val kind: Kind,
        /** Whether THIS poll fetched fresh data: success | timeout | not_ready. */
        val retrievalStatus: String? = null,
        val taskId: String? = null,
        /** e.g. `local_bash`, `local_workflow`, `subagent`. */
        val taskType: String? = null,
        /** running | completed (poll); `stopped` for a stop; Grok's own word, lower-cased. */
        val status: String? = null,
        val exitCode: Int? = null,
        /** Shell output (may hold ANSI escapes). */
        val output: String? = null,
        /** The original command (a stop's, Grok's, CodeBuddy's shell). */
        val command: String? = null,
        /** A stop's acknowledgement. */
        val message: String? = null,
    ) {
        enum class Kind { POLL, STOP }
    }

    enum class Badge { RUNNING, COMPLETED, FAILED, STOPPED }

    /** One task of a lane: the latest poll's outcome plus the freshest output and command
     *  gathered across every poll of that task id. */
    data class Row(
        val key: String,
        val taskId: String?,
        val taskType: String?,
        val badge: Badge,
        /** The latest poll is still in flight with nothing back yet: a spinner. */
        val isInFlight: Boolean,
        val exitCode: Int?,
        val output: String?,
        val command: String?,
        /** How many polls fold into this row. */
        val pollCount: Int,
        /** A sub-agent rather than a background shell: its output is a prose report
         *  (Markdown), not a terminal stream. */
        val isSubagent: Boolean,
        /** Its polls' call ids, in order; the last is the latest. */
        val pollIds: List<String>,
    )

    /** A command that moved its process to the background, as its task's row names it (web
     *  `BackgroundLaunch`). */
    data class Launch(
        /** The launching call. */
        val callId: String,
        val command: String,
        /** The agent's own one-line description of the command. */
        val description: String?,
    )

    // region Envelopes

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private fun tagRe(name: String) = Regex("<$name>([\\s\\S]*?)</$name>", RegexOption.IGNORE_CASE)

    private val RETRIEVAL_STATUS = tagRe("retrieval_status")
    private val TASK_TYPE = tagRe("task_type")
    private val TASK_ID = tagRe("task_id")
    private val STATUS = tagRe("status")
    private val EXIT_CODE = tagRe("exit_code")

    /** Greedy to the LAST close, so shell output that itself holds `<…>` is not cut short. */
    private val OUTPUT_TAG = Regex("<output>([\\s\\S]*)</output>", RegexOption.IGNORE_CASE)

    // JS `/^\r?\n/` and `/\r?\n[ \t]*$/` (no `m` flag): the input's own ends. Java's `$` would
    // also match before a final line break, hence `\z`.
    private val LEADING_NEWLINE = Regex("^\\r?\\n")
    private val TRAILING_NEWLINE = Regex("\\r?\\n[ \\t]*\\z")

    /** A tag a Claude Code poll envelope cannot do without. */
    private val POLL_TAG = Regex("<(?:retrieval_status|task_type|task_id)>", RegexOption.IGNORE_CASE)

    private val INTEGER = Regex("-?\\d+")

    private fun xmlTag(re: Regex, text: String): String? = re.find(text)?.groupValues?.get(1)?.trim()

    private fun xmlOutputTag(text: String): String? {
        val inner = OUTPUT_TAG.find(text)?.groupValues?.get(1) ?: return null
        return inner.replaceFirst(LEADING_NEWLINE, "").replaceFirst(TRAILING_NEWLINE, "")
    }

    private fun parsePoll(text: String): Envelope? {
        val retrievalStatus = xmlTag(RETRIEVAL_STATUS, text)
        val taskType = xmlTag(TASK_TYPE, text)
        val taskId = xmlTag(TASK_ID, text)
        val status = xmlTag(STATUS, text)
        // At least one envelope-defining tag, so text that merely holds a stray `<task_id>`
        // isn't misread as a poll.
        if (retrievalStatus == null && taskType == null && !(taskId != null && status != null)) return null
        val exitRaw = xmlTag(EXIT_CODE, text)
        val exitCode = exitRaw?.takeIf { INTEGER.matches(it) }?.toIntOrNull()
        return Envelope(
            kind = Envelope.Kind.POLL,
            retrievalStatus = retrievalStatus,
            taskId = taskId,
            taskType = taskType,
            status = status,
            exitCode = exitCode,
            output = xmlOutputTag(text),
        )
    }

    private fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val STOPPED_PHRASE = Regex("successfully stopped task", RegexOption.IGNORE_CASE)

    private fun parseStop(obj: JsonObject): Envelope? {
        val message = obj.str("message")
        val command = obj.str("command")
        val taskId = obj.str("task_id")
        val taskType = obj.str("task_type")
        // Strict: a stop acknowledgement, so other JSON results aren't hijacked. The phrase is
        // the signal; the id + command + message + type shape a fallback for wording drift.
        val looksLikeStop = (message != null && STOPPED_PHRASE.containsMatchIn(message)) ||
            (taskId != null && command != null && message != null && taskType != null)
        if (!looksLikeStop) return null
        return Envelope(
            kind = Envelope.Kind.STOP,
            taskId = taskId,
            taskType = taskType,
            status = "stopped",
            command = command,
            message = message,
        )
    }

    /** Statuses Grok reports for a task that was ended rather than allowed to finish: the stop
     *  kind, so the row reads "stopped". */
    private val GROK_STOPPED = setOf("killed", "stopped", "cancelled", "canceled")

    /** A finite JSON number, as an exit code. */
    private fun exitOf(value: JsonElement?): Int? {
        val p = value as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        val d = p.doubleOrNull?.takeIf { it.isFinite() } ?: return null
        return if (d == Math.floor(d) && kotlin.math.abs(d) <= Int.MAX_VALUE) d.toInt() else null
    }

    private fun grokResult(result: JsonObject): Envelope? {
        val taskId = result.str("task_id")
        val command = result.str("command")
        val status = result.str("status")?.trim()?.lowercase()
        // Something identifying, so a stray `{type:"TaskOutput"}` isn't a real (blank) row.
        if (taskId == null && command == null && status == null) return null
        return Envelope(
            kind = if (status != null && status in GROK_STOPPED) Envelope.Kind.STOP else Envelope.Kind.POLL,
            taskId = taskId,
            status = status,
            exitCode = exitOf(result["exit_code"]),
            output = result.str("output"),
            command = command,
        )
    }

    /**
     * The result objects of a Grok `TaskOutput` envelope: `Result` (one task), `MultiResult`
     * (the first array of objects in it, its field names not pinned by any capture), and
     * `TaskNotFound` (none). Insertion order, as JavaScript reads it.
     */
    private fun grokResultObjects(envelope: JsonObject): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        (envelope["Result"] as? JsonObject)?.let(out::add)
        (envelope["MultiResult"] as? JsonObject)?.let { multi ->
            multi.values.firstOrNull { it is JsonArray }?.let { arr ->
                (arr as JsonArray).forEach { (it as? JsonObject)?.let(out::add) }
            }
        }
        return out
    }

    /**
     * Grok 0.2.11x's flat `SubagentCompleted`: what a poll of a background SUB-AGENT returns.
     * Its command reads like 0.2.9x's `[subagent:<type>] <description>`, so both eras draw the
     * same row.
     */
    private fun grokSubagentCompleted(envelope: JsonObject): Envelope? {
        fun nonEmpty(key: String) = envelope.str(key)?.takeIf { it.isNotEmpty() }
        val taskId = nonEmpty("subagent_id")
        val status = nonEmpty("status")?.trim()?.lowercase()
        val output = nonEmpty("output") ?: nonEmpty("result")
        if (taskId == null && status == null && output == null) return null
        val label = nonEmpty("subagent_type")?.let { "[subagent:$it]" }
        val description = nonEmpty("description")
        val command = if (label != null && description != null) "$label $description" else label ?: description
        return Envelope(
            kind = if (status != null && status in GROK_STOPPED) Envelope.Kind.STOP else Envelope.Kind.POLL,
            taskId = taskId,
            taskType = "subagent",
            status = status,
            output = output,
            command = command,
        )
    }

    /** A Grok `get_command_or_subagent_output` result. Strict on `type`, so other JSON output
     *  is never taken; a truncated envelope is no envelope. */
    private fun parseGrokTaskOutput(envelope: JsonObject): List<Envelope> {
        return when (envelope.str("type")) {
            "SubagentCompleted" -> listOfNotNull(grokSubagentCompleted(envelope))
            "TaskOutput" -> grokResultObjects(envelope).mapNotNull(::grokResult)
            else -> emptyList()
        }
    }

    /** Statuses CodeBuddy reports for an ended task: the stop kind. */
    private val CODEBUDDY_STOPPED = setOf("cancelled", "canceled", "killed")

    /** The `<system-reminder data-role="tool-hint">` block CodeBuddy appends to a still-running
     *  snapshot, addressed to the model. */
    private val CODEBUDDY_TOOL_HINT = Regex("<system-reminder\\b[\\s\\S]*?</system-reminder>", RegexOption.IGNORE_CASE)

    /** Separates an agent snapshot's header (and its `Prompt:` / `Response:` echoes) from the
     *  worker's own report. */
    private const val CODEBUDDY_RESULT_SEPARATOR = "--- RESULT ---"

    private fun labeledLine(line: String?, label: String): String? {
        if (line == null) return null
        val prefix = "$label: "
        if (!line.startsWith(prefix)) return null
        return line.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
    }

    private fun cleanCodeBuddyBody(body: String): String? =
        body.replace(CODEBUDDY_TOOL_HINT, "").trim().takeIf { it.isNotEmpty() }

    /**
     * A CodeBuddy `TaskOutput` snapshot: plain text, two shapes —
     *
     *     agent → Task ID: … / Status: … / … / --- RESULT --- / <report>
     *     shell → Shell ID: … / Command: … / Status: … / Duration: … / Timestamp: … /
     *             (blank) / Stdout (full): … / Stderr …
     *
     * The agent shape shows only the `--- RESULT ---` tail (the `Response:` above it repeats it).
     * The shell shape is read only when its header can be located with certainty
     * ([shellHeaderIsUnambiguous]).
     */
    private fun parseCodeBuddySnapshot(text: String): Envelope? {
        val lines = text.split("\n")
        fun kindFor(s: String?) = if (s != null && s in CODEBUDDY_STOPPED) Envelope.Kind.STOP else Envelope.Kind.POLL

        val agentId = labeledLine(lines.getOrNull(0), "Task ID")
        if (agentId != null) {
            val status = labeledLine(lines.getOrNull(1), "Status")?.trim()?.lowercase() ?: return null
            val separator = lines.indexOf(CODEBUDDY_RESULT_SEPARATOR)
            // No separator: a team-member snapshot, whose whole body is the note after the header.
            val body = lines.drop(if (separator >= 0) separator + 1 else 2).joinToString("\n")
            return Envelope(
                kind = kindFor(status),
                taskId = agentId,
                taskType = "subagent",
                status = status,
                output = cleanCodeBuddyBody(body),
            )
        }

        val shellId = labeledLine(lines.getOrNull(0), "Shell ID") ?: return null
        val command = labeledLine(lines.getOrNull(1), "Command")
        if (command == null || !shellHeaderIsUnambiguous(lines)) return null
        val status = labeledLine(lines.getOrNull(2), "Status")?.trim()?.lowercase()
        return Envelope(
            kind = kindFor(status),
            taskId = shellId,
            taskType = "local_bash",
            status = status,
            // No exit code in the snapshot: leaving it null keeps the badge from inventing a failure.
            exitCode = null,
            // From the `Stdout (…)` line on: stdout AND stderr, in the order they were written.
            output = cleanCodeBuddyBody(lines.drop(6).joinToString("\n")),
            command = command,
        )
    }

    /**
     * The header's closing run sits at its fixed offset (line 2) AND nowhere else. The command
     * and the output are both arbitrary text, so a second candidate makes the boundary
     * undecidable — and then nothing is claimed, rather than a truncated command and a status
     * lifted from the output.
     */
    private fun shellHeaderIsUnambiguous(lines: List<String>): Boolean {
        if (!shellHeaderRunAt(lines, 2)) return false
        for (i in 3 until lines.size) if (shellHeaderRunAt(lines, i)) return false
        return true
    }

    /** `Status:` / `Duration:` / `Timestamp:` / blank / `Stdout…`, from [i]. */
    private fun shellHeaderRunAt(lines: List<String>, i: Int): Boolean {
        val blank = lines.getOrNull(i + 3)
        return lines.getOrNull(i).orEmpty().startsWith("Status: ") &&
            lines.getOrNull(i + 1).orEmpty().startsWith("Duration: ") &&
            lines.getOrNull(i + 2).orEmpty().startsWith("Timestamp: ") &&
            blank != null && blank.trim().isEmpty() &&
            lines.getOrNull(i + 4).orEmpty().startsWith("Stdout")
    }

    /**
     * Every task one background-task result reports: one envelope for Claude Code's poll / stop
     * and CodeBuddy's snapshot, one per task for Grok's `TaskOutput`. Empty when the text is none
     * of them (the call then draws as itself). Each shape is recognisable up front, so ordinary
     * output skips the parsers.
     */
    fun parseEnvelopes(text: String?): List<Envelope> {
        val raw = text?.trim()
        if (raw.isNullOrEmpty()) return emptyList()
        // Parsed once, for both JSON shapes; a truncated envelope (an enormous log) is no envelope.
        val obj by lazy(LazyThreadSafetyMode.NONE) { if (raw.startsWith("{")) parseObject(raw) else null }
        val single = (if (POLL_TAG.containsMatchIn(raw)) parsePoll(raw) else null)
            ?: obj?.let(::parseStop)
            ?: (if (raw.startsWith("Task ID: ") || raw.startsWith("Shell ID: ")) parseCodeBuddySnapshot(raw) else null)
        if (single != null) return listOf(single)
        return obj?.let(::parseGrokTaskOutput).orEmpty()
    }

    /** The first task a result reports, or null. */
    fun parseEnvelope(text: String?): Envelope? = parseEnvelopes(text).firstOrNull()

    // endregion

    // region Launches

    /** Claude Code's four wordings for a command that went on in the background (a
     *  `run_in_background` launch, the user's Ctrl+B, a message that arrived while it ran, a
     *  timeout), then the task id. */
    private val LAUNCH = Regex(
        "Command (?:running in background with ID:|was manually backgrounded by user with ID:|was moved to the background \\(ID:|did not complete within its \\d+s timeout and was moved to the background \\(ID:)\\s*([A-Za-z0-9_-]+)",
        RegexOption.IGNORE_CASE,
    )

    /** Grok's `run_terminal_command(background: true)` acknowledgement. */
    private val GROK_LAUNCH = Regex("Background task\\s+([A-Za-z0-9_-]+)\\s+started", RegexOption.IGNORE_CASE)

    /** OpenCode 2's acknowledgement for a command run in (or moved to) the background. */
    private val OPENCODE_LAUNCH = Regex("Command moved to the background \\(shell ID:\\s*([A-Za-z0-9_-]+)\\)", RegexOption.IGNORE_CASE)

    /** Claude Code's `Monitor` that started: the task its events come from. */
    private val MONITOR_STARTED = Regex("^Monitor started \\(task ([A-Za-z0-9_-]+)[,)]")

    /** The task a background launch result names, or null (web `parseBackgroundLaunch`). */
    fun parseLaunch(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val match = LAUNCH.find(text) ?: GROK_LAUNCH.find(text) ?: OPENCODE_LAUNCH.find(text)
        return match?.groupValues?.get(1)
    }

    private fun isMonitor(vm: ToolCallVM) = vm.rawName.trim().lowercase() == "monitor"

    /**
     * The task [vm] left running in the background, when it is a command (or a monitor) whose
     * result said so — and, for the card, whether it watches rather than runs. Null otherwise.
     */
    fun launchedTask(vm: ToolCallVM): Pair<String, Boolean>? {
        if (vm.isError) return null
        val output = vm.output?.takeIf { it.isNotBlank() } ?: vm.content
        if (isMonitor(vm)) {
            return MONITOR_STARTED.find(output?.trimStart().orEmpty())?.groupValues?.get(1)?.let { it to true }
        }
        if (vm.bucket != ToolKindBucket.EXECUTE) return null
        return parseLaunch(output)?.let { it to false }
    }

    /** What started [vm]'s task, for its row: its command line and the agent's description of it. */
    fun launchOf(vm: ToolCallVM): Pair<String, Launch>? {
        val (taskId, _) = launchedTask(vm) ?: return null
        val args = ToolDerive.parseJson(vm.input)
        val display = when (val c = args?.get("command") ?: args?.get("cmd")) {
            is JsonPrimitive -> c.content.takeIf { c.isString }
            is JsonArray -> c.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(" ")
            else -> null
        } ?: (args?.get("ws") as? JsonObject)?.str("url")
            ?: vm.input?.takeIf { args == null && it.isNotBlank() }
            ?: return null
        val description = args?.str("description")?.trim()?.takeIf { it.isNotEmpty() && it != display.trim() }
        return taskId to Launch(vm.id, display, description)
    }

    // endregion

    // region Which calls

    private val TOOL_NAMES = setOf("taskoutput", "taskstop", "task_output", "task_stop")

    private fun isInFlight(vm: ToolCallVM) = vm.state == ToolCallState.RUNNING || vm.state == ToolCallState.INPUT_STREAMING

    /** What a poll returned: its output, else the live call's content. */
    private fun resultText(vm: ToolCallVM): String? = vm.output?.takeIf { it.isNotBlank() } ?: vm.content

    /**
     * A poll's input: Claude Code's `{task_id, block?, timeout?}` or Grok's `{task_ids: [...],
     * timeout_ms}`. The block / timeout keys tell it from `cancel_delegation` / `TaskStop` (a
     * bare `{task_id}`) and `get_delegation_status` (`{task_ids, wait_ms}`); a `subagent_type`
     * rules out a real sub-agent call.
     */
    private fun inputIsPoll(input: String?): Boolean {
        val obj = ToolDerive.parseJson(input) ?: return false
        if ("subagent_type" in obj) return false
        obj.str("task_id")?.takeIf { it.isNotEmpty() }?.let { return "block" in obj || "timeout" in obj }
        val ids = obj["task_ids"] as? JsonArray ?: return false
        return ids.isNotEmpty() && ids.all { it is JsonPrimitive && it.isString } && "timeout_ms" in obj
    }

    /**
     * Whether [vm] is a background-task poll or stop (web `isBackgroundTaskToolCall`): named
     * `TaskOutput` / `TaskStop`; its result parses as an envelope (a live alias, Grok's JSON,
     * CodeBuddy's text, any naming); or it has a poll's input and is still in flight. Never a
     * real sub-agent call.
     *
     * The input shape claims only a call in flight: Grok's `get_command_or_subagent_output`
     * polls commands and sub-agents with the same arguments, so once it settles its result alone
     * decides — an unknown result falls back to a plain card rather than a row stuck on running.
     *
     * Which lane a codeg delegation call belongs to is decided before this, by name
     * ([MessageRender.groupBackgroundTasks]).
     */
    fun isToolCall(vm: ToolCallVM): Boolean {
        if (vm.rawName.trim().lowercase() in TOOL_NAMES) return true
        if (parseEnvelopes(resultText(vm)).isNotEmpty()) return true
        return inputIsPoll(vm.input) && isInFlight(vm)
    }

    // endregion

    // region Rows

    /** The task a poll was called for: `task_id`, or Grok's one-task `task_ids` (which lets an
     *  in-flight poll join its settled sibling). */
    private fun inputTaskId(input: String?): String? {
        val obj = ToolDerive.parseJson(input) ?: return null
        obj.str("task_id")?.takeIf { it.isNotEmpty() }?.let { return it }
        val ids = obj["task_ids"] as? JsonArray ?: return null
        return (ids.singleOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    }

    private fun badge(envelope: Envelope?, poll: ToolCallVM): Badge {
        if (envelope?.kind == Envelope.Kind.STOP) return Badge.STOPPED
        // Grok says how it ended in `status`; checked before `completed` so a failure with no
        // exit code (a spawn error) is not a success.
        if (envelope?.status == "failed") return Badge.FAILED
        if (envelope?.status == "completed") {
            return if (envelope.exitCode != null && envelope.exitCode != 0) Badge.FAILED else Badge.COMPLETED
        }
        // An errored poll with no clean "completed" envelope is a failure.
        if (poll.isError || poll.state == ToolCallState.ERROR) return Badge.FAILED
        // running / timeout / not_ready / no envelope yet.
        return Badge.RUNNING
    }

    /** `[subagent:<type>] …`: Grok 0.2.9x's pseudo-command for a sub-agent. */
    private const val GROK_SUBAGENT_PREFIX = "[subagent:"

    private fun isSubagentRow(taskType: String?, command: String?): Boolean =
        taskType == "subagent" || command?.trimStart()?.startsWith(GROK_SUBAGENT_PREFIX) == true

    private class Entry(val poll: ToolCallVM, val envelope: Envelope?)

    /**
     * A run of polls → one row per task, in first-seen order (web `buildBackgroundTaskRows`). A
     * poll belongs to its envelope's task id, else its input's, so a task polled N times is one
     * row with its latest outcome, and parallel tasks are one row each.
     */
    fun buildRows(polls: List<ToolCallVM>): List<Row> {
        val order = mutableListOf<String>()
        val byKey = HashMap<String, Pair<String?, MutableList<Entry>>>()

        fun record(poll: ToolCallVM, envelope: Envelope?) {
            val taskId = envelope?.taskId ?: inputTaskId(poll.input)
            // A poll naming no task and reporting nothing yet says nothing a row could show. Live,
            // that is a poll whose `task_id` hasn't streamed in (an arg-less first `tool_call`):
            // each re-poll would stack an anonymous "running" row, and it joins its task's row
            // once the id arrives. ADAPTATION: the web keys this on the call being unsettled;
            // a reply handed off locally settles every call (with an empty result), so an
            // id-less, envelope-less poll that returned nothing is left out too.
            if (taskId == null && envelope == null && (isInFlight(poll) || resultText(poll).isNullOrBlank())) return
            val key = taskId ?: "__bg__:${poll.id}"
            val entry = byKey.getOrPut(key) {
                order += key
                taskId to mutableListOf()
            }
            entry.second += Entry(poll, envelope)
        }

        for (poll in polls) {
            // One poll can report several tasks (Grok's `MultiResult`).
            val envelopes = parseEnvelopes(resultText(poll))
            if (envelopes.isEmpty()) record(poll, null) else envelopes.forEach { record(poll, it) }
        }

        return order.map { key ->
            val (taskId, entries) = byKey.getValue(key)
            val latest = entries.last()
            val envelope = latest.envelope
            // The terminal poll carries the full output; an in-flight last poll must not blank a
            // run an earlier poll captured.
            var output: String? = null
            var command: String? = null
            var taskType: String? = null
            for (item in entries) {
                item.envelope?.output?.takeIf { it.isNotBlank() }?.let { output = it }
                item.envelope?.command?.takeIf { it.isNotEmpty() }?.let { command = it }
                item.envelope?.taskType?.takeIf { it.isNotEmpty() }?.let { taskType = it }
            }
            val subagent = isSubagentRow(taskType, command)
            Row(
                key = key,
                taskId = taskId,
                taskType = taskType,
                badge = badge(envelope, latest.poll),
                isInFlight = isInFlight(latest.poll) && envelope?.output == null,
                exitCode = envelope?.exitCode,
                output = if (subagent) stripGrokSubagentScaffolding(output) else output,
                command = command,
                pollCount = entries.size,
                isSubagent = subagent,
                pollIds = entries.map { it.poll.id }.distinct(),
            )
        }
    }

    private val SUBAGENT_META = Regex("<subagent_meta>[\\s\\S]*?</subagent_meta>", RegexOption.IGNORE_CASE)
    private val SUBAGENT_RESULT = Regex("<subagent_result>[\\s\\S]*?</subagent_result>", RegexOption.IGNORE_CASE)
    private val BLANK_LINE_RUN = Regex("\\n{3,}")

    /**
     * Drop what Grok appends to a POLLED sub-agent's report for the parent model
     * (`<subagent_meta>…`, `<subagent_result>… resume_from=…`). Output without them comes back
     * as it was.
     */
    fun stripGrokSubagentScaffolding(output: String?): String? {
        if (output.isNullOrEmpty()) return output
        val stripped = output.replace(SUBAGENT_META, "").replace(SUBAGENT_RESULT, "")
        if (stripped == output) return output
        // The tags sit at the end: close the blank lines they leave.
        return stripped.replace(BLANK_LINE_RUN, "\n\n").trimEnd()
    }

    // endregion

    // region Status and text

    /** The lane's status (web `backgroundStatus`): any task running, then any failed; every
     *  task stopped is interrupted. */
    fun status(rows: List<Row>): StepStatus = when {
        rows.any { it.badge == Badge.RUNNING } -> StepStatus.RUNNING
        rows.any { it.badge == Badge.FAILED } -> StepStatus.FAILED
        rows.isNotEmpty() && rows.all { it.badge == Badge.STOPPED } -> StepStatus.CANCELLED
        else -> StepStatus.SUCCEEDED
    }

    /** One row's status. A settled poll that said "still running" is a snapshot, not live work:
     *  only a poll still in flight is running. */
    fun rowStatus(row: Row): StepStatus = when (row.badge) {
        Badge.COMPLETED -> StepStatus.SUCCEEDED
        Badge.FAILED -> StepStatus.FAILED
        Badge.STOPPED -> StepStatus.CANCELLED
        Badge.RUNNING -> if (row.isInFlight) StepStatus.RUNNING else StepStatus.SUCCEEDED
    }

    /** Kept in view while its reply is folded: its newest poll is still in flight (web
     *  `BackgroundTaskRows` under `pinnedOnly`). */
    fun staysInView(row: Row): Boolean = row.badge == Badge.RUNNING && row.isInFlight

    /**
     * What a failed task is pointed at by ("show the failure", [ActivitySummary.firstUnrecoveredId]):
     * not a call id, because one poll can report several tasks (Grok's `MultiResult`) and only one
     * of them failed. Unique per row: a poll belongs to one lane.
     */
    fun failureKey(row: Row): String = "bg-row:${row.pollIds.lastOrNull().orEmpty()}:${row.key}"

    /** The task id as the row shows it. */
    fun shortId(row: Row): String? = row.taskId?.take(9)

    private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** What the row shows of the task's output. No ANSI colours are drawn here, so a shell's
     *  escapes are taken out (a sub-agent's report is prose and has none). */
    fun displayOutput(row: Row): String? {
        val output = row.output ?: return null
        return if (row.isSubagent) output else output.replace(ANSI_ESCAPE, "")
    }

    /**
     * `Regenerate audit report · report.md written`: what started the task (the launching
     * command's description, else its first line), then what it came to — a failure's last
     * line, a success's first.
     */
    fun rowDetail(row: Row, launch: Launch?): String? {
        val what = launch?.description ?: if (row.isSubagent) null else firstLine(launch?.command ?: row.command)
        val output = displayOutput(row)
        val result = when (row.badge) {
            Badge.RUNNING -> null
            Badge.FAILED -> lastLine(output)
            Badge.COMPLETED, Badge.STOPPED -> firstLine(output)
        }
        return listOfNotNull(what, result).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
    }

    private fun nonBlankLines(text: String?): List<String> =
        text?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun firstLine(text: String?): String? = nonBlankLines(text).firstOrNull()?.take(160)

    fun lastLine(text: String?): String? = nonBlankLines(text).lastOrNull()?.take(160)

    // endregion
}
