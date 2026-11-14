package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What codeg's delegation tools say, read off their arguments and results (codeg web 0.44
 * `lib/delegation-card.ts` and `lib/delegation-status.ts`). Pure, so the card, the rail marker
 * and the transcript-wide task index all read one account.
 *
 * Results reach a card in many shapes: the broker's bare report, an MCP `CallToolResult`
 * (`structuredContent` and/or the report as `content[0].text`), Codex's `Wall time:…Output:`
 * text around it, codex-acp's `{result, error}` envelope, and codex code-mode's
 * `Promise.allSettled` entry (`{index, status:"fulfilled", value}`). Each reader peels what it
 * can and falls back to the text.
 */
object DelegationParse {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // region JSON helpers

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    /** A non-string number field (a stringified one is not the broker's). */
    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun parse(raw: String): JsonElement? = runCatching { json.parseToJsonElement(raw) }.getOrNull()

    /**
     * The first `{…}` in [raw] that parses as an object, trying the widest span first (web
     * `extractEmbeddedJsonObject`): Codex wraps a result in `Wall time: …\nOutput:\n<json>`.
     */
    fun embeddedObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var end = raw.lastIndexOf('}')
        while (end > start) {
            (parse(raw.substring(start, end + 1)) as? JsonObject)?.let { return it }
            end = raw.lastIndexOf('}', end - 1)
        }
        return null
    }

    /**
     * A `Promise.allSettled` entry codex code-mode returns per call: the fulfilled value (an
     * object, or JSON text of one), or — rejected — the reason as [Settled.rejected].
     */
    private data class Settled(val obj: JsonObject?, val rejected: String?)

    private fun settle(obj: JsonObject): Settled {
        val status = obj.str("status")
        if (status == "fulfilled" && obj.containsKey("value")) {
            return when (val value = obj["value"]) {
                is JsonObject -> Settled(value, null)
                is JsonPrimitive -> if (value.isString) {
                    Settled(parse(value.content) as? JsonObject ?: embeddedObject(value.content), null)
                } else {
                    Settled(obj, null)
                }
                else -> Settled(obj, null)
            }
        }
        if (status == "rejected" && obj.containsKey("reason")) {
            val reason = when (val r = obj["reason"]) {
                is JsonPrimitive -> r.content
                is JsonObject -> r.str("message") ?: r.toString()
                null, JsonNull -> null
                else -> r.toString()
            }
            return Settled(null, reason?.takeIf { it.isNotBlank() } ?: "Rejected")
        }
        return Settled(obj, null)
    }

    /** [raw] as an object: parsed, re-parsed once when double-encoded, else scanned for one. */
    private fun objectOf(raw: String): JsonObject? = when (val v = parse(raw)) {
        is JsonObject -> v
        is JsonPrimitive -> if (v.isString) parse(v.content) as? JsonObject ?: embeddedObject(v.content) else null
        null -> embeddedObject(raw)
        else -> null
    }

    private fun firstContentText(obj: JsonObject): String? =
        ((obj["content"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("text")

    // endregion

    // region Call arguments

    /** What a delegate or resume call was asked: who, what, where — or which task, and why. */
    data class Args(
        val agentType: String? = null,
        val task: String? = null,
        val workingDir: String? = null,
        /** A resume's (and a check's) task. */
        val taskId: String? = null,
        /** Why a resume was asked for. */
        val reason: String? = null,
        /** The assistant the call names: the backend then ignores its `agent_type`. */
        val assistantId: Int? = null,
    )

    private val argsWrappers = listOf("arguments", "input", "params", "payload", "_meta", "args")
    private val argKeys = listOf("task", "agent_type", "working_dir", "task_id", "reason")

    private fun findArgs(value: JsonElement?, depth: Int = 0): JsonObject? {
        if (depth > 4 || value == null) return null
        if (value is JsonPrimitive) return if (value.isString) findArgs(parse(value.content), depth + 1) else null
        val obj = value as? JsonObject ?: return null
        if (argKeys.any { (obj[it] as? JsonPrimitive)?.isString == true }) return obj
        if (CodeModeDelegation.coerceAssistantId(obj["assistant_id"]) != null) return obj
        for (key in argsWrappers) findArgs(obj[key], depth + 1)?.let { return it }
        return null
    }

    /**
     * The call's arguments, peeling the wrappers hosts nest them in (web `parseInput`) — or, for
     * an OpenCode code-mode `execute`, read out of the object literal in its `code`
     * ([CodeModeDelegation.args]): a code-mode call whose arguments can't be read is still one,
     * with nothing known.
     */
    fun args(raw: String?): Args {
        if (raw.isNullOrBlank()) return Args()
        val parsed = parse(raw)
        val obj = findArgs(parsed) ?: return CodeModeDelegation.args(parsed) ?: Args()
        fun field(key: String) = obj.str(key)?.trim()?.takeIf { it.isNotEmpty() }
        return Args(
            agentType = CodeModeDelegation.coerceAgentType(obj.str("agent_type")),
            assistantId = CodeModeDelegation.coerceAssistantId(obj["assistant_id"]),
            task = obj.str("task")?.takeIf { it.isNotBlank() },
            workingDir = field("working_dir"),
            taskId = field("task_id"),
            reason = obj.str("reason")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    // endregion

    // region A delegate / resume call's own result

    /**
     * A `delegate_to_agent` (or `resume_delegation`) result (web `ParsedToolOutput`). Under async
     * delegation the result is a running ACK; only a fast-complete or a legacy synchronous call
     * returns the OUTCOME, so an ack must never read as a finished task.
     */
    data class CallResult(
        val ack: Boolean,
        val text: String? = null,
        val isError: Boolean = false,
        val childConversationId: Int? = null,
        val agentType: String? = null,
        val assistantName: String? = null,
        val taskId: String? = null,
        val errorCode: String? = null,
        /** A broker report was recovered, not only text: what [refusedResume] trusts. */
        val structured: Boolean = false,
    )

    private fun interpretReport(obj: JsonObject): CallResult? {
        val child = obj.int("child_conversation_id")
        val agent = obj.str("agent_type")?.trim()?.takeIf { it.isNotEmpty() }
        val assistant = obj.str("assistant_name")?.trim()?.takeIf { it.isNotEmpty() }
        val taskId = obj.str("task_id")
        val code = obj.str("error_code")
        fun ack() = CallResult(true, null, false, child, agent, assistant, taskId, code, structured = true)
        val status = obj.str("status")
        if (status != null) {
            return when (status) {
                "completed" -> CallResult(false, obj.str("text").orEmpty(), false, child, agent, assistant, taskId, code, structured = true)
                "failed", "canceled" -> CallResult(
                    false, obj.str("message") ?: code ?: "Delegation failed.", true, child, agent, assistant, taskId, code, structured = true,
                )
                else -> ack()
            }
        }
        return when (obj.str("kind")) {
            "ok" -> CallResult(false, obj.str("text").orEmpty(), false, child, agent, assistant, taskId, null, structured = true)
            "err" -> CallResult(
                false, obj.str("message") ?: obj.str("code") ?: "Delegation failed.", true, child, agent, assistant, taskId,
                obj.str("code"), structured = true,
            )
            else -> null
        }
    }

    private fun isResolvableCallResult(obj: JsonObject): Boolean =
        obj["status"] is JsonPrimitive || obj["kind"] is JsonPrimitive || McpResultEnvelope.isCallToolResult(obj)

    /** The broker's running ack, as a host that keeps only the content text shows it. */
    private const val ACK_TEXT = "Delegation successful."

    /** A delegate call's result (web `parseToolOutput`); [forceError] for the error channel. */
    fun callResult(raw: String?, forceError: Boolean = false): CallResult? =
        parseCallResult(raw, forceError)?.let { r ->
            // Bare ack text, its report dropped by the host: still an ack, not a finished task.
            if (!r.structured && !r.isError && r.text?.trimStart()?.startsWith(ACK_TEXT) == true) {
                r.copy(ack = true, text = null, taskId = r.taskId ?: taskId(r.text))
            } else {
                r
            }
        }

    private fun parseCallResult(raw: String?, forceError: Boolean): CallResult? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        var obj: JsonObject = when (val v = parse(trimmed)) {
            is JsonObject -> v
            is JsonPrimitive, is JsonArray -> return CallResult(false, if (v is JsonPrimitive) v.content else trimmed, forceError)
            else -> embeddedObject(trimmed) ?: return CallResult(false, trimmed, forceError)
        }
        val settled = settle(obj)
        settled.rejected?.let { return CallResult(false, it, true) }
        obj = settled.obj ?: return null
        val peeled = McpResultEnvelope.peel(obj, ::isResolvableCallResult)
        obj = peeled.obj
        if (obj["content"] is JsonArray) {
            val outerError = (obj["isError"] as? JsonPrimitive)?.content == "true"
            val sc = obj["structuredContent"] as? JsonObject
            (sc?.let(::interpretReport) ?: fromContent(obj))?.let { r ->
                return if (!r.ack && (outerError || forceError)) r.copy(isError = true) else r
            }
            firstContentText(obj)?.let { text ->
                return CallResult(false, text, outerError || forceError, sc?.int("child_conversation_id"))
            }
        }
        interpretReport(obj)?.let { r -> return if (!r.ack && forceError) r.copy(isError = true) else r }
        peeled.hostError?.let { return CallResult(false, it, true) }
        return CallResult(false, "```json\n${prettyPrintJsonString(obj.toString()) ?: obj}\n```", forceError)
    }

    /** A report inlined in `content[0]` when the host kept no `structuredContent`. */
    private fun fromContent(obj: JsonObject): CallResult? {
        val first = (obj["content"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        (first["json"] as? JsonObject)?.let(::interpretReport)?.let { return it }
        return first.str("text")?.let(::embeddedObject)?.let(::interpretReport)
    }

    private val taskIdText = Regex("task_id[=:]\\s*\"?([A-Za-z0-9][\\w-]*)\"?")

    /**
     * The broker's task id a delegate call's result names (web `parseDelegateTaskId`): the
     * structured field first, then the `task_id=<id>` the running ack's text carries.
     */
    fun taskId(output: String?): String? {
        val trimmed = output?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        objectOf(trimmed)?.let { found ->
            val obj = settle(found).obj ?: return@let
            val result = McpResultEnvelope.peel(obj) { it["task_id"] is JsonPrimitive || isResolvableCallResult(it) }.obj
            (result["structuredContent"] as? JsonObject)?.str("task_id")?.let { return it }
            result.str("task_id")?.let { return it }
        }
        return taskIdText.find(trimmed)?.groupValues?.get(1)
    }

    private const val NOT_RESUMABLE = "not_resumable"
    private const val REFUSED_TEXT = "Not resumed:"

    /**
     * Whether a `resume_delegation` result is the broker REFUSING (web `isRefusedResume`). A
     * refusal reports the task's real status, so only its code tells — or, where no structure
     * survived, the message's opening words.
     */
    fun refusedResume(output: String?): Boolean {
        val parsed = callResult(output)
        if (parsed?.structured == true) return parsed.errorCode == NOT_RESUMABLE
        return listOfNotNull(parsed?.text, output).any { it.trimStart().startsWith(REFUSED_TEXT) }
    }

    /**
     * What a refused resume explains itself with ("Not resumed: the task already completed —
     * …"): the report's own `message` where it carries the refusal — for a `completed` report
     * the content text a host shows may be the child's earlier output — then whatever the
     * result shows, then the raw result. Null for an empty result.
     */
    fun refusalMessage(output: String?): String? {
        val raw = output?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val candidates = mutableListOf<String?>()
        objectOf(raw)?.let { found ->
            val obj = settle(found).obj ?: return@let
            val result = McpResultEnvelope.peel(obj, ::isResolvableCallResult).obj
            val content = firstContentText(result)
            candidates += (result["structuredContent"] as? JsonObject)?.str("message")
            candidates += result.str("message")
            candidates += content?.let(::embeddedObject)?.str("message")
            candidates += content
        }
        candidates += callResult(raw)?.text
        candidates += raw
        val texts = candidates.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        return texts.firstOrNull { it.startsWith(REFUSED_TEXT) } ?: texts.firstOrNull()
    }

    // endregion

    // region Status checks (`get_delegation_status` / `cancel_delegation`)

    enum class TaskStatus { RUNNING, COMPLETED, FAILED, CANCELED, UNKNOWN }

    /** How far a sub-agent had got (Rust `DelegationTaskReport.progress`). */
    data class Progress(val steps: Int, val lastStep: String?)

    /** One task's report from a check (web `StatusReport`). */
    data class StatusReport(
        val status: TaskStatus? = null,
        val taskId: String? = null,
        /** The result (completed), the reason (failed), or the progress note (running). */
        val text: String? = null,
        val errorCode: String? = null,
        val durationMs: Long? = null,
        /** A running report whose sub-agent is parked on the user's decision. */
        val blocked: Boolean = false,
        val progress: Progress? = null,
        val agentType: String? = null,
        val childConversationId: Int? = null,
    )

    private fun taskStatus(obj: JsonObject?): TaskStatus? = when (obj?.str("status")) {
        "running" -> TaskStatus.RUNNING
        "completed" -> TaskStatus.COMPLETED
        "failed" -> TaskStatus.FAILED
        "canceled" -> TaskStatus.CANCELED
        "unknown" -> TaskStatus.UNKNOWN
        else -> null
    }

    /**
     * Whether [obj] is a report. Trusted `structuredContent` needs only a status; anything read
     * out of text — a child's own output can be JSON with a `status` — must name its task too.
     */
    private fun isReport(obj: JsonObject?, trusted: Boolean): Boolean =
        taskStatus(obj) != null && (trusted || obj?.str("task_id") != null)

    // The backend's running sentinels (`running_report` / `attach_blocked`), English protocol
    // strings: on a host that keeps only content text they are the only sign of "still running".
    private const val RUNNING_MARKER = "running."
    private const val REPLY_PREFIX = "latest sub-agent reply:"
    private const val BLOCKED_PREFIX = "awaiting your decision:"
    private const val LEGACY_RUNNING = "sub-agent is still running in the background."

    private fun textRunning(text: String?): Boolean {
        val normalized = text?.trim()?.lowercase() ?: return false
        if (normalized == LEGACY_RUNNING) return true
        val newline = normalized.indexOf('\n')
        val first = (if (newline < 0) normalized else normalized.substring(0, newline)).trim()
        if (first != RUNNING_MARKER) return false
        if (newline < 0) return true
        val second = normalized.substring(newline + 1).trimStart()
        return second.startsWith(REPLY_PREFIX) || second.startsWith(BLOCKED_PREFIX)
    }

    private fun textBlocked(text: String?): Boolean {
        val newline = text?.indexOf('\n') ?: return false
        if (newline < 0) return false
        return text.substring(newline + 1).trimStart().lowercase().startsWith(BLOCKED_PREFIX)
    }

    private fun progressOf(value: JsonElement?): Progress? {
        val obj = value as? JsonObject ?: return null
        val steps = obj.int("steps")?.takeIf { it >= 0 } ?: return null
        val marks = (obj["marks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } .orEmpty()
            .filter { it == "ok" || it == "failed" }
        val last = obj.str("last_step")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        return Progress(maxOf(steps, marks.size), last)
    }

    private fun reportOf(obj: JsonObject, displayText: String? = null): StatusReport {
        val text = displayText ?: obj.str("text") ?: obj.str("message")
        return StatusReport(
            status = taskStatus(obj),
            taskId = obj.str("task_id"),
            text = text,
            errorCode = obj.str("error_code"),
            durationMs = obj.number("duration_ms")?.toLong(),
            blocked = obj["blocked_on"] is JsonObject || textBlocked(text),
            progress = progressOf(obj["progress"]),
            agentType = obj.str("agent_type")?.trim()?.takeIf { it.isNotEmpty() },
            childConversationId = obj.int("child_conversation_id"),
        )
    }

    private fun isResolvableResult(obj: JsonObject): Boolean =
        taskStatus(obj) != null || obj["tasks"] is JsonArray || McpResultEnvelope.isCallToolResult(obj)

    private data class ResultObject(val obj: JsonObject?, val hostError: String?)

    private fun resultObject(raw: String): ResultObject {
        val found = objectOf(raw) ?: return ResultObject(null, null)
        val settled = settle(found)
        settled.rejected?.let { return ResultObject(null, it) }
        val obj = settled.obj ?: return ResultObject(null, null)
        val peeled = McpResultEnvelope.peel(obj, ::isResolvableResult)
        return ResultObject(peeled.obj, peeled.hostError)
    }

    /** One report out of a check's result (web `parseStatusReport`). */
    fun statusReport(output: String?): StatusReport {
        val raw = output?.trim().orEmpty()
        if (raw.isEmpty()) return StatusReport()
        val (obj, hostError) = resultObject(raw)
        if (obj == null) {
            val text = hostError ?: raw
            return StatusReport(status = if (textRunning(text)) TaskStatus.RUNNING else null, text = text, blocked = textBlocked(text))
        }
        val contentText = firstContentText(obj)
        val sc = obj["structuredContent"] as? JsonObject
        when {
            isReport(sc, trusted = true) -> return reportOf(sc!!, contentText)
            isReport(obj, trusted = false) -> return reportOf(obj, contentText)
            contentText != null -> embeddedObject(contentText)?.takeIf { isReport(it, trusted = false) }?.let { return reportOf(it) }
        }
        val fallback = contentText ?: hostError ?: raw
        return StatusReport(status = if (textRunning(fallback)) TaskStatus.RUNNING else null, text = fallback, blocked = textBlocked(fallback))
    }

    /**
     * A check's result as one report per task (web `parseStatusReports`): a `{tasks:[…]}` batch
     * wherever it sits, index-aligned with the call's `task_ids`; else the single report.
     */
    fun statusReports(output: String?): List<StatusReport> {
        val raw = output?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            resultObject(raw).obj?.let { obj ->
                val sc = obj["structuredContent"] as? JsonObject
                val (tasks, trusted) = when {
                    sc?.get("tasks") is JsonArray -> sc["tasks"] as JsonArray to true
                    obj["tasks"] is JsonArray -> obj["tasks"] as JsonArray to false
                    else -> (firstContentText(obj)?.let(::embeddedObject)?.get("tasks") as? JsonArray) to false
                }
                if (tasks != null && tasks.isNotEmpty() && tasks.any { isReport(it as? JsonObject, trusted) }) {
                    return tasks.map { reportOf(it as? JsonObject ?: JsonObject(emptyMap())) }
                }
            }
        }
        return listOf(statusReport(output))
    }

    private val taskIdWrappers = listOf("arguments", "input", "params", "payload", "_meta")

    private fun findTaskIds(value: JsonElement?, depth: Int = 0): List<String> {
        if (depth > 4 || value == null) return emptyList()
        if (value is JsonPrimitive) return if (value.isString) findTaskIds(parse(value.content), depth + 1) else emptyList()
        val obj = value as? JsonObject ?: return emptyList()
        val out = LinkedHashSet<String>()
        (obj["task_ids"] as? JsonArray)?.forEach { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }?.let(out::add) }
        obj.str("task_id")?.let(out::add)
        if (out.isNotEmpty()) return out.toList()
        for (key in taskIdWrappers) findTaskIds(obj[key], depth + 1).takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    /** The task(s) a check or a cancel names — `task_ids`, or a legacy single `task_id`. */
    fun checkedTaskIds(input: String?): List<String> =
        if (input.isNullOrBlank()) emptyList() else findTaskIds(parse(input))

    /** What a check's row says (web `BadgeStatus`). [CHECKED]: it came back "still running" —
     *  a settled snapshot, not live work. */
    enum class Badge { STARTING, RUNNING, CHECKED, WAITING, OK, ERR }

    data class ResolvedBadge(val badge: Badge, val errorCode: String? = null)

    /** Web `deriveBadge`: the report's own status first, else the call's lifecycle. */
    fun badge(cancel: Boolean, report: StatusReport, state: ToolCallState, hasError: Boolean): ResolvedBadge =
        when (report.status) {
            TaskStatus.COMPLETED -> ResolvedBadge(Badge.OK)
            TaskStatus.RUNNING -> ResolvedBadge(if (report.blocked) Badge.WAITING else Badge.CHECKED)
            TaskStatus.UNKNOWN -> ResolvedBadge(Badge.ERR, "unknown")
            TaskStatus.FAILED -> ResolvedBadge(Badge.ERR, report.errorCode)
            // Canceling is what a cancel is for; to a check, a canceled task failed.
            TaskStatus.CANCELED -> if (cancel) ResolvedBadge(Badge.OK) else ResolvedBadge(Badge.ERR, report.errorCode ?: "canceled")
            null -> when {
                state == ToolCallState.ERROR || hasError -> ResolvedBadge(Badge.ERR)
                state == ToolCallState.DONE -> ResolvedBadge(Badge.OK)
                else -> ResolvedBadge(Badge.RUNNING)
            }
        }

    /** How a task ended, as a check reported it — null while it still runs (web `taskEnding`). */
    fun ending(report: StatusReport?): ResolvedBadge? {
        if (report?.status == null || report.status == TaskStatus.RUNNING) return null
        return badge(false, report, ToolCallState.DONE, false).takeIf { it.badge == Badge.OK || it.badge == Badge.ERR }
    }

    /** A check, read once: its reports and the ids it asked about. */
    data class ParsedCheck(val vm: ToolCallVM, val reports: List<StatusReport>, val askedIds: List<String>) {
        /** Every task this check is about. */
        val taskIds: Set<String> get() = (reports.mapNotNull { it.taskId } + askedIds).toSet()
    }

    fun parseCheck(vm: ToolCallVM): ParsedCheck =
        ParsedCheck(vm, statusReports(vm.output?.takeIf { it.isNotBlank() } ?: vm.content), checkedTaskIds(vm.input))

    /** One task across a run of checks (web `DelegationTaskRow`): its newest report, and how
     *  often it was checked. */
    data class TaskRow(
        val key: String,
        val taskId: String?,
        val report: StatusReport,
        val badge: ResolvedBadge,
        val checks: Int,
        val latestCallId: String,
    )

    /**
     * Checks → one row per task, in order of first appearance (web `buildDelegationTaskRows`).
     * A report goes to its own task id, else the call's by position; one with neither keeps a
     * row of its own. A check still running that names nothing yet is left out — a host that
     * fills the ids in later would otherwise stack anonymous rows.
     */
    fun taskRows(checks: List<ParsedCheck>, cancel: Boolean = false): List<TaskRow> {
        val order = ArrayList<String>()
        val byKey = HashMap<String, MutableList<Pair<ParsedCheck, StatusReport>>>()
        val ids = HashMap<String, String?>()
        for (check in checks) {
            val vm = check.vm
            val unsettled = vm.state == ToolCallState.RUNNING || vm.state == ToolCallState.INPUT_STREAMING
            val count = maxOf(check.reports.size, check.askedIds.size)
            for (i in 0 until count) {
                val report = check.reports.getOrNull(i) ?: StatusReport()
                val taskId = report.taskId ?: check.askedIds.getOrNull(i)
                if (taskId == null && unsettled && report.status == null && report.text.isNullOrBlank()) continue
                val key = taskId ?: "__unattributed__:${vm.id}:$i"
                val entry = byKey.getOrPut(key) {
                    order.add(key)
                    ids[key] = taskId
                    mutableListOf()
                }
                entry.add(check to report)
            }
        }
        return order.map { key ->
            val entries = byKey.getValue(key)
            val (check, report) = entries.last()
            TaskRow(
                key = key,
                taskId = ids[key],
                report = report,
                badge = badge(cancel, report, check.vm.state, check.vm.isError),
                checks = entries.size,
                latestCallId = check.vm.id,
            )
        }
    }

    // endregion
}
