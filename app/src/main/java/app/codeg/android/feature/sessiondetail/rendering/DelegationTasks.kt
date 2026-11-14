package app.codeg.android.feature.sessiondetail.rendering

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import app.codeg.android.core.model.DelegationBinding

/**
 * What the thread's checks say about each delegated task (web `linkLaterDelegationPolls`): the
 * agent hands a task over, goes on, and asks after it later — often in another turn — yet the
 * newest of those checks is how the delegation stands. Each delegate call gets the newest check
 * on its task; a resume of the task takes over the checks that follow it.
 */
@Immutable
data class DelegationTaskIndex(
    /** The newest check on each delegate (or resume) call's task, by that call's id. */
    val byCall: Map<String, DelegationParse.TaskRow> = emptyMap(),
    /** The agent each task was handed to, by task id: what a check's row is labelled with. */
    val agentByTask: Map<String, String> = emptyMap(),
) {
    companion object {
        val EMPTY = DelegationTaskIndex()
    }
}

/** The thread's [DelegationTaskIndex]. Provided by the session screen. */
val LocalDelegationTasks = staticCompositionLocalOf { DelegationTaskIndex.EMPTY }

/**
 * Builds a [DelegationTaskIndex] from a thread's calls in order. Folded in two stages so the
 * persisted transcript is read once and only the live reply is re-read as it streams:
 * [copy] the persisted fold, then [add] the live calls to the copy.
 */
class DelegationTaskFold private constructor(
    private val ownerByTask: HashMap<String, String>,
    private val taskByCall: HashMap<String, String>,
    private val checksByCall: HashMap<String, MutableList<DelegationParse.ParsedCheck>>,
    private val agentByTask: HashMap<String, String>,
) {
    constructor() : this(HashMap(), HashMap(), HashMap(), HashMap())

    fun copy(): DelegationTaskFold = DelegationTaskFold(
        HashMap(ownerByTask),
        HashMap(taskByCall),
        HashMap(checksByCall.mapValues { it.value.toMutableList() }),
        HashMap(agentByTask),
    )

    /** One call, in thread order. [bindings] are the live connection's, by delegate call id. */
    fun add(vm: ToolCallVM, bindings: Map<String, DelegationBinding>) {
        when {
            Delegations.drawsCard(vm) -> {
                val binding = bindings[vm.id]
                val output = vm.output?.takeIf { it.isNotBlank() } ?: vm.content
                // A refused resume revived nothing: the checks after it are still the run's before.
                if (Delegations.isResumeCall(vm.rawName) && DelegationParse.refusedResume(output)) return
                val taskId = Delegations.callTaskId(vm, binding) ?: return
                ownerByTask[taskId] = vm.id
                taskByCall[vm.id] = taskId
                val agent = binding?.agentType?.takeIf { it.isNotEmpty() }
                    ?: DelegationParse.args(vm.input).agentType
                    ?: DelegationParse.callResult(output)?.agentType
                    ?: Delegations.fromMeta(vm.meta)?.agentType
                agent?.let { agentByTask[taskId] = it }
            }
            Delegations.isStatusPoll(vm.rawName) -> {
                val check = DelegationParse.parseCheck(vm)
                check.reports.forEach { r -> if (r.taskId != null && r.agentType != null) agentByTask.putIfAbsent(r.taskId, r.agentType) }
                check.taskIds.mapNotNullTo(LinkedHashSet()) { ownerByTask[it] }.forEach { owner ->
                    checksByCall.getOrPut(owner) { mutableListOf() }.add(check)
                }
            }
        }
    }

    fun index(): DelegationTaskIndex {
        val byCall = HashMap<String, DelegationParse.TaskRow>()
        for ((call, checks) in checksByCall) {
            val rows = DelegationParse.taskRows(checks)
            (rows.firstOrNull { it.taskId == taskByCall[call] } ?: rows.lastOrNull())?.let { byCall[call] = it }
        }
        return DelegationTaskIndex(byCall, HashMap(agentByTask))
    }
}
