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
