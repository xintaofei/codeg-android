package app.codeg.android.feature.sessiondetail.rendering

// A reply's activity (codeg web 0.44 `src/lib/activity`): what each of its calls was as far as
// the timeline is concerned, and which of them fold into one row.

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
    if (vm.isPlanMode) return null
    return when (vm.bucket) {
        ToolKindBucket.READ, ToolKindBucket.SEARCH -> StepFamily.EXPLORE
        ToolKindBucket.WEB -> if (vm.rawName.trim().lowercase() in BROWSER_NAMES) null else StepFamily.EXPLORE
        ToolKindBucket.EXECUTE -> StepFamily.COMMAND
        ToolKindBucket.EDIT, ToolKindBucket.TODO, ToolKindBucket.TASK, ToolKindBucket.OTHER -> null
    }
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
