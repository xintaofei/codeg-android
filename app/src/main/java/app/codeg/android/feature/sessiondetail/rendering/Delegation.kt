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
        )
    }
}
