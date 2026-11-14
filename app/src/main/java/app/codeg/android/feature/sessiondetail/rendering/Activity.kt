package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

// A reply's activity (codeg web 0.44 `src/lib/activity`): what each of its calls was as far as
// the timeline is concerned, which of them fold into one row, what the reply's header counts,
// and which of its parts are the answer that stays in view when its steps are folded away.

/**
 * The two kinds of call a run of which folds into one row (web `GroupKind`): reading the
 * workspace or the web, and running commands. Everything else stands as a row of its own.
 */
enum class StepFamily { EXPLORE, COMMAND }

/** A browser drives a page, it doesn't look something up: never part of an exploration. */
private val BROWSER_NAMES = setOf("browser", "browser_action")

/**
 * Which run [vm] may join, or null when it stands alone. Edits, MCP and other tools,
 * sub-agents, todo lists and plan-mode transitions each say something of their own, so they
 * end a run rather than being folded away into it. Web `familyOf` over `EXPLORE_KINDS` /
 * `COMMAND_KINDS`.
 */
fun stepFamily(vm: ToolCallVM): StepFamily? {
    if (vm.isPlanMode || isMcpCall(vm)) return null
    return when (vm.bucket) {
        ToolKindBucket.READ, ToolKindBucket.SEARCH -> StepFamily.EXPLORE
        ToolKindBucket.WEB -> if (vm.rawName.trim().lowercase() in BROWSER_NAMES) null else StepFamily.EXPLORE
        ToolKindBucket.EXECUTE -> StepFamily.COMMAND
        ToolKindBucket.EDIT, ToolKindBucket.TODO, ToolKindBucket.TASK, ToolKindBucket.OTHER -> null
    }
}

/** Hosts whose `<prefix>.<tool>` names are their own built-ins, not an MCP server's. */
private val NON_MCP_PREFIXES = setOf("functions", "multi_tool_use", "tools")
private val MCP_PREFIXED = Regex("^mcp__(.+?)__(.+)$", RegexOption.IGNORE_CASE)
private val MCP_SEPARATED = Regex("^([A-Za-z0-9][\\w-]*)[/.:]([A-Za-z0-9][\\w.-]*)$")

/**
 * Whether [vm] is a call to an MCP server's tool (web `parseMcpToolName`): `mcp__<server>__<tool>`,
 * or `<server>/<tool>` (also `.` / `:`) as other hosts spell it. Read off the name as sent: the
 * display bucket strips the server, so `mcp__fs__read_file` would pass for a plain read.
 *
 * The separated spelling also fits a bare file name (`README.md`), which some hosts title a
 * live read by; a call whose host declared what kind it is doesn't take it.
 */
internal fun isMcpCall(vm: ToolCallVM): Boolean {
    val name = vm.rawName.trim()
    MCP_PREFIXED.find(name)?.let { return it.groupValues[1].isNotEmpty() && it.groupValues[2].isNotEmpty() }
    val declared = vm.kind.isNotBlank() && !vm.kind.equals("other", ignoreCase = true)
    if (declared) return false
    val separated = MCP_SEPARATED.find(name) ?: return false
    return separated.groupValues[1].lowercase() !in NON_MCP_PREFIXES
}

/**
 * One status vocabulary for every step (web `StepStatus`, less the states Android has no
 * source for). A failed call is told apart from one the user declined ([REJECTED]) and one
 * the user stopped mid-flight ([CANCELLED], "interrupted"): only the first is a failure of
 * the agent's.
 */
enum class StepStatus { RUNNING, SUCCEEDED, FAILED, REJECTED, CANCELLED }

/** Claude Code's result for a call declined in the permission prompt, and the generic
 *  "rejected/denied by user" wording other adapters use. */
private val REJECTED_RE = Regex(
    "^The user doesn't want to proceed with this tool use|\\b(?:rejected|denied|declined) by (?:the )?user\\b",
    RegexOption.IGNORE_CASE,
)

/** Claude Code's result for a call the user stopped mid-flight. */
private val INTERRUPTED_RE = Regex("^\\[Request interrupted by user", RegexOption.IGNORE_CASE)

fun stepStatus(vm: ToolCallVM): StepStatus = when (vm.state) {
    ToolCallState.RUNNING, ToolCallState.INPUT_STREAMING -> StepStatus.RUNNING
    ToolCallState.DONE -> StepStatus.SUCCEEDED
    ToolCallState.ERROR -> {
        val text = (vm.output ?: vm.content ?: "").trim()
        when {
            INTERRUPTED_RE.containsMatchIn(text) -> StepStatus.CANCELLED
            REJECTED_RE.containsMatchIn(text) -> StepStatus.REJECTED
            else -> StepStatus.FAILED
        }
    }
}

/**
 * A call interrupted before it carried anything: no arguments, no output beyond the
 * interruption marker. It says nothing a row could show, so it is left out of the timeline
 * (the header still counts it as interrupted). Web `isBlankInterruption`.
 */
fun isBlankInterruption(vm: ToolCallVM): Boolean {
    if (stepStatus(vm) != StepStatus.CANCELLED) return false
    val input = vm.input?.trim()
    return input.isNullOrEmpty() || input == "{}" || input == "[]" || input == "null"
}

/**
 * What a reply's activity header says (web `ActivitySummary`, less its breakdown by kind:
 * a tooltip there, and nothing here shows one).
 */
data class ActivitySummary(
    /** Tool-like steps. Thoughts, prose, plans and compactions don't count, nor bookkeeping
     *  calls (todo lists, delegation status checks, shell-session continuations). */
    val steps: Int = 0,
    val failed: Int = 0,
    /** Failures no later step redid successfully. */
    val unrecovered: Int = 0,
    /** Calls the user stopped: not failures. */
    val interrupted: Int = 0,
    /** The call of the first unrecovered failure, for "show the failure". */
    val firstUnrecoveredId: String? = null,
)

/**
 * Count a reply's steps and judge its failures (web `summarizeActivity`). A failure is
 * recovered when a later step of the same kind on the same target succeeded — the same
 * command line, the same file, the same tool with the same arguments ([retrySignature]).
 * A call the user declined is a failure; one they stopped is interrupted.
 *
 * [parts] in reply order and before [MessageRender.groupConsecutiveTools], which drops the
 * blank interruptions this still counts.
 */
fun summarizeActivity(parts: List<RenderPart>): ActivitySummary {
    val calls = parts.flatMap {
        when (it) {
            is RenderPart.Tool -> listOf(it.vm)
            is RenderPart.ToolGroup -> it.items
            else -> emptyList()
        }
    }
    var steps = 0
    var interrupted = 0
    val failures = mutableListOf<Pair<Int, ToolCallVM>>()
    val lastSuccess = mutableMapOf<String, Int>()
    calls.forEachIndexed { index, vm ->
        if (!isBookkeeping(vm)) steps++
        when (stepStatus(vm)) {
            StepStatus.CANCELLED -> interrupted++
            StepStatus.FAILED, StepStatus.REJECTED -> failures += index to vm
            StepStatus.SUCCEEDED -> retrySignature(vm)?.let { lastSuccess[it] = index }
            StepStatus.RUNNING -> Unit
        }
    }
    val unrecovered = failures.filter { (index, vm) ->
        val redone = retrySignature(vm)?.let(lastSuccess::get)
        redone == null || redone < index
    }
    return ActivitySummary(
        steps = steps,
        failed = failures.size,
        unrecovered = unrecovered.size,
        interrupted = interrupted,
        firstUnrecoveredId = unrecovered.firstOrNull()?.second?.id,
    )
}

/** Continuations and bookkeeping, not steps of their own. */
private fun isBookkeeping(vm: ToolCallVM): Boolean =
    vm.bucket == ToolKindBucket.TODO ||
        CodexCodeMode.isShellSessionName(vm.rawName) ||
        vm.rawName.trim().lowercase().let { it == "get_delegation_status" || it.endsWith("__get_delegation_status") || it.endsWith("/get_delegation_status") }

/**
 * What "the same thing" means for the recovery rule (web `retrySignature`), or null when a
 * call of this kind is never considered retried: a sub-agent, a todo list, a plan-mode
 * transition.
 */
internal fun retrySignature(vm: ToolCallVM): String? {
    if (vm.isPlanMode) return null
    val args = ToolDerive.effectiveArgs(ToolDerive.parseJson(vm.input))
    fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        when (val v = args?.get(key)) {
            is JsonPrimitive -> v.content.takeIf { v.isString && it.isNotBlank() }
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(" ").takeIf { it.isNotBlank() }
            else -> null
        }
    }
    fun squash(text: String) = text.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    val squeezed = (vm.input ?: "").filterNot(Char::isWhitespace)
    val name = vm.rawName.trim().lowercase()
    // Before the bucket, which reads an MCP tool by its bare name: the same tool with the
    // same arguments, never a read of the same path.
    if (isMcpCall(vm)) return "mcp:$name:$squeezed"
    return when (vm.bucket) {
        ToolKindBucket.EXECUTE -> when {
            CodexCodeMode.isShellSessionName(vm.rawName) -> null
            CodexCodeMode.isScriptName(vm.rawName) -> CodexCodeMode.parseScript(args)?.source?.let(::squash)?.let { "script:$it" }
            // A command persisted as its bare text (codex `exec_command`) is not JSON.
            else -> (field("command", "cmd") ?: vm.input?.takeIf { args == null })?.let(::squash)?.let { "command:$it" }
        }
        ToolKindBucket.EDIT -> {
            val paths = vm.diffFiles?.map { it.path }?.filter { it.isNotBlank() }?.sorted().orEmpty()
                .ifEmpty { listOfNotNull(field("file_path", "path", "filename", "file", "target_file", "notebook_path")) }
            paths.takeIf { it.isNotEmpty() }?.let { "edit:${it.joinToString("\n")}" }
        }
        ToolKindBucket.READ -> field("file_path", "path", "filename", "file", "target_file")?.let { "read:$it" }
        ToolKindBucket.SEARCH, ToolKindBucket.WEB, ToolKindBucket.OTHER -> "$name:$squeezed"
        ToolKindBucket.TODO, ToolKindBucket.TASK -> null
    }
}

/**
 * Whether [part] is the reply's answer rather than the process that produced it (web
 * `isTurnAnswerPart`): prose, an image, a failure in the agent's words. Everything else —
 * calls, thoughts, compactions — is process. Exhaustive on purpose: a new part kind fails
 * to compile here until it is classified.
 */
fun isAnswerPart(part: RenderPart): Boolean = when (part) {
    is RenderPart.Text, is RenderPart.Image, is RenderPart.Error -> true
    is RenderPart.Reasoning, is RenderPart.Tool, is RenderPart.ToolGroup, is RenderPart.Compaction, is RenderPart.Unknown -> false
}

/**
 * Where a reply's trailing answer starts: after its last process part (web
 * `splitTrailingAnswerParts`). Answer parts BEFORE a process part stay with the process —
 * prose followed by more work is a note between steps, not a wrap-up, and lifting it out
 * would reorder the reply. A reply of answer parts alone starts its answer at 0.
 */
fun answerStart(parts: List<RenderPart>): Int {
    var end = parts.size
    while (end > 0 && isAnswerPart(parts[end - 1])) end--
    return end
}
