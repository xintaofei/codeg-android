package app.codeg.android.feature.sessiondetail.rendering

import app.codeg.android.core.model.ToolApprovalInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime

/** `allow_once` / `allow_always` / a rejection: ACP's two reject kinds both turned the call away. */
enum class ApprovalDecision { ALLOW_ONCE, ALLOW_ALWAYS, REJECTED }

/**
 * How a tool call's permission request was answered, as the transcript draws it: a chip on the
 * call's row (codeg web 0.44 `lib/activity/approval.ts`). Built from the connection's live
 * record, or from the stamp a reopened conversation's detail load puts on the call.
 */
data class ToolApproval(
    val decision: ApprovalDecision,
    /** The chosen option's label in the agent's own words. */
    val optionName: String?,
    /** `user`, `chat_channel`, `cli_policy`; anything else reads as unknown. */
    val origin: String?,
    /** RFC 3339. */
    val decidedAt: String?,
)

object ToolApprovals {

    /** Where a detail load stamps a call's approval (Rust `APPROVAL_META_KEY`). */
    const val META_KEY = "codeg.approval"

    private fun decisionOf(kind: String?): ApprovalDecision? = when (kind) {
        "allow_once" -> ApprovalDecision.ALLOW_ONCE
        "allow_always" -> ApprovalDecision.ALLOW_ALWAYS
        "reject_once", "reject_always" -> ApprovalDecision.REJECTED
        else -> null
    }

    private fun text(value: String?): String? = value?.takeIf { it.isNotBlank() }

    /** The approval a detail load stamped on a call's [meta], if any. */
    fun fromMeta(meta: JsonObject?): ToolApproval? {
        val stamp = meta?.get(META_KEY) as? JsonObject ?: return null
        fun field(key: String) = (stamp[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val decision = decisionOf(field("option_kind")) ?: return null
        return ToolApproval(decision, text(field("option_name")), text(field("origin")), text(field("decided_at")))
    }

    /** A live record as the transcript draws it. */
    fun fromInfo(info: ToolApprovalInfo): ToolApproval? {
        val decision = decisionOf(info.optionKind) ?: return null
        return ToolApproval(decision, text(info.optionName), text(info.origin), text(info.decidedAt))
    }

    /** [ToolApprovalInfo.decidedAt] in epoch ms; unreadable reads as the oldest possible. */
    internal fun decidedAtMs(iso: String): Long =
        runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrDefault(0L)

    /**
     * Fold [incoming] records into a connection's map: one per tool call, the later decision
     * winning. Idempotent and order-free, so a snapshot older than the events already applied
     * can only add calls this client never saw answered, never walk one back. Returns
     * [current] itself when nothing changed.
     */
    fun merge(current: Map<String, ToolApprovalInfo>, incoming: List<ToolApprovalInfo>): Map<String, ToolApprovalInfo> {
        var next: MutableMap<String, ToolApprovalInfo>? = null
        for (info in incoming) {
            if (info.toolCallId.isEmpty()) continue
            val known = (next ?: current)[info.toolCallId]
            if (known != null && decidedAtMs(known.decidedAt) >= decidedAtMs(info.decidedAt)) continue
            val into = next ?: LinkedHashMap(current).also { next = it }
            into[info.toolCallId] = info
        }
        return next ?: current
    }
}

/**
 * What the live connection knows about the thread that the transcript doesn't (web
 * `ActivityLiveValue`): which call a pending permission request is about, whether the agent is
 * blocked on the user at all, and how the connection's permission requests were answered.
 */
data class ActivityLive(
    val awaitingToolCallId: String? = null,
    /** A permission request, a question or a plan to approve is open: the agent waits on the
     *  user rather than working. */
    val awaitingUser: Boolean = false,
    val approvals: Map<String, ToolApprovalInfo> = emptyMap(),
    /** The connection's delegations, by the `delegate_to_agent` call that started each. */
    val delegations: Map<String, app.codeg.android.core.model.DelegationBinding> = emptyMap(),
)

/** A tool call as the live connection sees it (web `useLiveToolStep`). */
data class LiveToolStep(
    /** The pending permission request is about this call. */
    val awaiting: Boolean,
    /** The call reads "declined": its result says so, or the user declined it and the
     *  transcript only shows it failing. */
    val declined: Boolean,
    val approval: ToolApproval?,
) {
    /** Whether the row draws its chip: a call already reading "declined" would only say it twice. */
    val showsApproval: Boolean get() = approval != null && !(declined && approval.decision == ApprovalDecision.REJECTED)
}

/**
 * - The call a pending permission request is about waits on the user, whatever its transcript
 *   state says — unless it has already settled.
 * - A call whose request was answered carries the answer: the connection's own record when it
 *   has one (the freshest), else the stamp on the call.
 */
fun liveToolStep(vm: ToolCallVM, live: ActivityLive): LiveToolStep {
    val settled = vm.state == ToolCallState.DONE || vm.state == ToolCallState.ERROR
    val awaiting = !settled && live.awaitingToolCallId != null && live.awaitingToolCallId == vm.id
    val approval = live.approvals[vm.id]?.let(ToolApprovals::fromInfo) ?: vm.approval
    val status = stepStatus(vm)
    val declined = !awaiting &&
        (status == StepStatus.REJECTED || (approval?.decision == ApprovalDecision.REJECTED && status == StepStatus.FAILED))
    return LiveToolStep(awaiting, declined, approval)
}
