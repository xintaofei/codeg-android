package app.codeg.android.feature.sessiondetail.rendering

import app.codeg.android.core.model.DelegationBinding
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A codeg delegation as its `delegate_to_agent` card draws it (codeg web 0.44
 * `lib/delegation-card.ts`, `use-delegation-card-model.ts`): which agent took the task, how it
 * stands, and the child session to open.
 */
data class DelegationView(
    val status: DelegationBinding.Status,
    val agentType: String?,
    val assistantName: String?,
    val task: String?,
    val childConversationId: Int?,
    val durationMs: Long?,
    val errorCode: String?,
    /** The broker's task id, mirrored on every stamp. */
    val taskId: String? = null,
)

object Delegations {

    /** Where the broker (live) and a detail load (history) stamp a delegation on its call. */
    const val META_KEY = "codeg.delegation"

    private fun bareName(name: String): String =
        name.trim().lowercase().substringAfterLast("__").substringAfterLast('/').substringAfterLast('.')

    /** A call that hands a task to another agent: `delegate_to_agent` or `resume_delegation`. */
    fun isDelegateCall(name: String): Boolean = bareName(name).let { it == "delegate_to_agent" || it == "resume_delegation" }

    /** A `resume_delegation` call: it re-runs a task under the ORIGINAL delegation's call id. */
    fun isResumeCall(name: String): Boolean = bareName(name) == "resume_delegation"

    private const val RESUMED_ACK = "Delegation resumed"
    private val CHILD_CONVERSATION = Regex("\"child_conversation_id\"\\s*:\\s*(\\d+)")

    /**
     * The binding a resume card stands for (web `useDelegationCardModel`): the broker re-binds
     * the child to the original call, so a resume finds it by the task id it names — and only
     * on this call's own evidence, since that id is an argument the model wrote: the result
     * names the same child conversation, or (a host that drops the structured report) affirms
     * the resume in words. A refusal ("Unknown task id") affirms nothing.
     */
    fun resumedBinding(vm: ToolCallVM, bindings: Collection<DelegationBinding>): DelegationBinding? {
        if (!isResumeCall(vm.rawName)) return null
        val taskId = (ToolDerive.parseJson(vm.input)?.get("task_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() } ?: return null
        val binding = bindings.firstOrNull { it.taskId == taskId } ?: return null
        val result = vm.output?.takeIf { it.isNotBlank() } ?: vm.content.orEmpty()
        val named = CHILD_CONVERSATION.find(result)?.groupValues?.get(1)?.toIntOrNull()
            ?: fromMeta(vm.meta)?.childConversationId
        if (named != null) return binding.takeIf { it.childConversationId == named }
        val text = (ToolOutputFormat.mcpResultText(result.trim()) ?: result).trim()
        return binding.takeIf { text.startsWith(RESUMED_ACK) }
    }

    /** A `get_delegation_status` check on a delegated task. */
    fun isStatusPoll(name: String): Boolean = bareName(name) == "get_delegation_status"

    /** The delegation stamped on a call's [meta] (web `parseDelegationMeta`), or null. */
    fun fromMeta(meta: JsonObject?): DelegationView? {
        val inner = meta?.get(META_KEY) as? JsonObject ?: return null
        fun str(key: String) = (inner[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        val status = when (str("status")) {
            "running", "pending" -> DelegationBinding.Status.RUNNING
            "completed", "ok" -> DelegationBinding.Status.OK
            "failed", "err" -> DelegationBinding.Status.ERR
            else -> return null
        }
        val child = (inner["child_conversation_id"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        return DelegationView(
            status = status,
            agentType = str("agent_type"),
            assistantName = str("assistant_name"),
            task = str("task_preview"),
            childConversationId = child,
            durationMs = (inner["duration_ms"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toLong(),
            errorCode = str("error_code"),
            taskId = str("task_id"),
        )
    }

    /**
     * The card's delegation: the live binding where there is one — the freshest account, and
     * the only one naming the child's agent for a live call — filling the gaps from the stamp.
     */
    fun view(vm: ToolCallVM, binding: DelegationBinding?): DelegationView? {
        val stamped = fromMeta(vm.meta)
        if (binding == null) return stamped
        return DelegationView(
            status = binding.status,
            agentType = binding.agentType.takeIf { it.isNotEmpty() } ?: stamped?.agentType,
            assistantName = binding.assistantName ?: stamped?.assistantName,
            task = binding.task ?: stamped?.task,
            childConversationId = binding.childConversationId ?: stamped?.childConversationId,
            durationMs = binding.durationMs ?: stamped?.durationMs,
            errorCode = binding.errorCode ?: stamped?.errorCode,
            taskId = binding.taskId ?: stamped?.taskId,
        )
    }

    /** A cancel of a delegated task. */
    fun isCancel(name: String): Boolean = bareName(name) == "cancel_delegation"

    /**
     * A call the delegation card draws: a delegate or resume, one the broker stamped, or an
     * OpenCode code-mode `execute` whose script calls `delegate_to_agent`. Each on the call's
     * own evidence — its name, the broker's stamp, the script — never on a name inferred from a
     * live title, which can quote anything (a Bash `rg 'delegate_to_agent'` would lose its
     * command and output to a sub-agent card).
     */
    fun drawsCard(vm: ToolCallVM): Boolean =
        isDelegateCall(vm.rawName) || vm.meta?.containsKey(META_KEY) == true ||
            CodeModeDelegation.isCall(vm.rawName, vm.input)

    /** A `resume_delegation` the broker refused: the task it named, and what it said. */
    data class ResumeRefusal(val taskId: String?, val message: String?)

    /**
     * The refusal a `resume_delegation` came back with (web `isRefusedResume`); null for any
     * other call, a resume that went ahead, and one still in flight. A refusal still names the
     * task's agent, child and real status — which would pass for a sub-agent that came back —
     * so it draws as just the refusal.
     */
    fun resumeRefusal(vm: ToolCallVM): ResumeRefusal? {
        if (!isResumeCall(vm.rawName)) return null
        val output = vm.output?.takeIf { it.isNotBlank() } ?: vm.content?.takeIf { it.isNotBlank() } ?: return null
        if (!DelegationParse.refusedResume(output)) return null
        return ResumeRefusal(DelegationParse.args(vm.input).taskId, DelegationParse.refusalMessage(output))
    }

    /** The task a delegate (or resume) call is about, from what the call itself carries. */
    fun callTaskId(vm: ToolCallVM, binding: DelegationBinding?): String? {
        val args = if (isResumeCall(vm.rawName)) DelegationParse.args(vm.input) else null
        return args?.taskId
            ?: DelegationParse.taskId(vm.output?.takeIf { it.isNotBlank() } ?: vm.content)
            ?: binding?.taskId
            ?: fromMeta(vm.meta)?.taskId
    }

    /**
     * Everything a delegation's card and rail marker say (web `useDelegationCardModel`), in
     * priority order: the live [binding] → the broker's stamp on [ToolCallVM.meta] → the call's
     * own arguments and result → the newest check on its task ([later]). Null when there is
     * nothing to draw it by, or for a resume the broker refused (that card states the reason).
     */
    fun model(vm: ToolCallVM, binding: DelegationBinding?, later: DelegationParse.TaskRow?): DelegationModel? {
        val resumed = isResumeCall(vm.rawName)
        val output = vm.output?.takeIf { it.isNotBlank() } ?: vm.content
        if (resumed && DelegationParse.refusedResume(output)) return null
        val args = DelegationParse.args(vm.input)
        val stamped = fromMeta(vm.meta)
        val result = DelegationParse.callResult(output, forceError = vm.isError)
        val hasModel = binding != null || stamped != null ||
            (!resumed && (args.agentType != null || args.assistantId != null || args.task != null)) ||
            result?.agentType != null || result?.childConversationId != null
        if (!hasModel) return null
        val report = later?.report
        val ending = DelegationParse.ending(report)
        val status = when {
            binding != null -> binding.status.asModel()
            stamped != null -> stamped.status.asModel()
            vm.state == ToolCallState.ERROR || vm.isError -> DelegationModel.Status.ERR
            ending != null -> if (ending.badge == DelegationParse.Badge.OK) DelegationModel.Status.OK else DelegationModel.Status.ERR
            result?.ack == true -> DelegationModel.Status.RUNNING
            result != null -> if (result.isError) DelegationModel.Status.ERR else DelegationModel.Status.OK
            vm.state == ToolCallState.DONE -> DelegationModel.Status.OK
            else -> DelegationModel.Status.STARTING
        }.let { s ->
            // A check that found it parked on the user: it waits, it doesn't work.
            if (s == DelegationModel.Status.RUNNING && report?.status == DelegationParse.TaskStatus.RUNNING && report.blocked) {
                DelegationModel.Status.WAITING
            } else {
                s
            }
        }
        val settled = status == DelegationModel.Status.OK || status == DelegationModel.Status.ERR
        // What it came back with: the newest check's report once the task ended, else a result
        // the call returned itself (a fast-complete or a synchronous broker).
        val outcome = when {
            !settled -> null
            ending != null -> report?.text
            // Only a broker report: bare text is a host's rendering of the ack, not a result.
            result != null && !result.ack && result.structured -> result.text
            else -> null
        }?.takeIf { it.isNotBlank() }
        return DelegationModel(
            status = status,
            agentType = binding?.agentType?.takeIf { it.isNotEmpty() }
                // A call that names an assistant: the backend ignores its `agent_type`.
                ?: args.agentType?.takeIf { !resumed && args.assistantId == null }
                ?: result?.agentType
                ?: stamped?.agentType
                ?: report?.agentType,
            assistantName = binding?.assistantName ?: result?.assistantName ?: stamped?.assistantName,
            task = args.task ?: binding?.task ?: stamped?.task,
            taskId = callTaskId(vm, binding) ?: later?.taskId,
            workingDir = args.workingDir,
            errorCode = binding?.errorCode ?: stamped?.errorCode ?: result?.errorCode ?: ending?.errorCode,
            childConversationId = binding?.childConversationId ?: stamped?.childConversationId
                ?: result?.childConversationId ?: report?.childConversationId,
            durationMs = binding?.durationMs ?: stamped?.durationMs ?: report?.durationMs?.takeIf { settled },
            outcome = outcome,
            progress = report?.progress,
            resumed = resumed,
            reason = if (resumed) args.reason else null,
        )
    }

    private fun DelegationBinding.Status.asModel(): DelegationModel.Status = when (this) {
        DelegationBinding.Status.RUNNING -> DelegationModel.Status.RUNNING
        DelegationBinding.Status.OK -> DelegationModel.Status.OK
        DelegationBinding.Status.ERR -> DelegationModel.Status.ERR
    }
}

/** A delegation as its card and its rail marker draw it (web `DelegationCardModel`). */
data class DelegationModel(
    val status: Status,
    val agentType: String?,
    /** The assistant the task ran as, when it ran as one. */
    val assistantName: String?,
    val task: String?,
    val taskId: String?,
    val workingDir: String?,
    val errorCode: String?,
    val childConversationId: Int?,
    val durationMs: Long?,
    /** What the task came back with, once it ended. */
    val outcome: String?,
    val progress: DelegationParse.Progress?,
    /** A `resume_delegation`: the sub-agent came back. */
    val resumed: Boolean,
    /** Why it was resumed, as the agent passed it on. */
    val reason: String?,
) {
    /** [STARTING]: no word from the broker yet. [WAITING]: parked on the user's decision. */
    enum class Status { STARTING, RUNNING, WAITING, OK, ERR }

    val working: Boolean get() = status == Status.STARTING || status == Status.RUNNING
}
