package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * A goal (codex `/goal`: `create_goal` … `update_goal`; claude's neutral goal) as its card
 * reads it (codeg web 0.44 `goal-tool-call.tsx` `parseGoal`, over one call).
 */
data class GoalView(
    val objective: String?,
    /** As sent, for an unknown status's label. */
    val status: String?,
    val tokensUsed: Double?,
    val tokenBudget: Double?,
    val timeUsedSeconds: Double?,
    val remainingTokens: Double?,
) {
    /** `active`, `paused`, `blocked`, `usage_limited`, `budget_limited`, `limited`, `complete`… */
    val normalizedStatus: String? get() = status?.lowercase()?.replace(Regex("[\\s-]+"), "_")

    val failed: Boolean
        get() = normalizedStatus in setOf("blocked", "usage_limited", "budget_limited", "limited", "failed")
}

/** What a goal card can do about the session's goal, provided by the session screen. */
data class GoalControl(
    /** Null without a live connection to send it on. */
    val onControl: ((String) -> Unit)?,
    /** The actions the adapter offers; empty while unknown. */
    val actions: List<String>,
    /**
     * The thread's latest goal call: the only card that may offer controls. A control acts on
     * the session's goal as it stands now, so an earlier goal's card offering one would pause
     * or clear a different goal.
     */
    val currentCallId: String? = null,
)

object Goals {

    private fun bareName(name: String) = name.trim().lowercase().substringAfterLast("__").substringAfterLast('/')

    fun isGoalCall(name: String): Boolean = bareName(name).let { it == "create_goal" || it == "update_goal" }

    /** The live title the server gives a structured goal update (`codex_goal.rs`). */
    private val UPDATE_TITLE = Regex("^goal updated\\s*\\(([^)]+)\\)\\s*[:：]\\s*([\\s\\S]*)$", RegexOption.IGNORE_CASE)

    /**
     * The goal call a live title names (web `parseGoalUpdateTitle`): `Goal updated (active): …`
     * opens a goal (`create_goal`), any other status updates it (`update_goal`). Null for any
     * other title.
     */
    fun nameFromTitle(title: String): String? {
        val m = UPDATE_TITLE.find(title.trim()) ?: return null
        val status = m.groupValues[1].trim().lowercase().replace(Regex("[\\s-]+"), "_")
        if (status.isEmpty() || m.groupValues[2].isBlank()) return null
        return if (status == "active") "create_goal" else "update_goal"
    }

    private fun obj(raw: String?): JsonObject? = raw?.let { ToolDerive.parseJson(it) }

    private fun JsonObject?.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { (this?.get(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }

    private fun JsonObject?.num(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { (this?.get(it) as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull?.takeIf { d -> d.isFinite() } }

    /** The goal [vm] states: its result's `goal` first, then its input; a create is active. */
    fun parse(vm: ToolCallVM): GoalView {
        val input = obj(vm.input)
        val output = obj(vm.output?.takeIf { it.isNotBlank() } ?: vm.content)
        val goal = output?.get("goal") as? JsonObject
        return GoalView(
            objective = goal.str("objective") ?: input.str("objective"),
            status = goal.str("status") ?: input.str("status") ?: if (bareName(vm.rawName) == "create_goal") "active" else null,
            tokensUsed = goal.num("tokensUsed", "tokens_used"),
            tokenBudget = goal.num("tokenBudget", "token_budget") ?: input.num("tokenBudget", "token_budget"),
            timeUsedSeconds = goal.num("timeUsedSeconds", "time_used_seconds"),
            remainingTokens = output.num("remainingTokens", "remaining_tokens"),
        )
    }

    /** `1.2K`, `340` — a token count as the card gives it. */
    fun tokens(count: Double): String =
        if (kotlin.math.abs(count) >= 1000) {
            String.format(java.util.Locale.ROOT, "%.1f", count / 1000).removeSuffix(".0") + "K"
        } else {
            count.toLong().toString()
        }

    /** `42s`, `3.5m`, `1.2h`. */
    fun duration(seconds: Double): String = when {
        seconds < 60 -> "${Math.round(seconds)}s"
        seconds < 3600 -> String.format(java.util.Locale.ROOT, "%.1f", seconds / 60).removeSuffix(".0") + "m"
        else -> String.format(java.util.Locale.ROOT, "%.1f", seconds / 3600).removeSuffix(".0") + "h"
    }
}
