package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.BuiltinAgent
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.ConversationDetail
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.network.ApiError

/**
 * "Fork from here": where a reply can fork, and how to tell the fork took (iOS `ReplyFork`).
 *
 * The backend names a fork point from the turn id it is sent (`resolve_fork_point`), and only
 * for the agents whose `session/fork` honours a chosen message: Claude Code and DeepSeek (by the
 * agent's message id, or the text's fingerprint), Codex (by the fingerprint alone). A point it
 * can't name it doesn't refuse: it forks at the tail. That is right at the end of the thread —
 * the tail IS the point — and a silent lie anywhere before it, so a reply earlier in the thread
 * offers a fork only where the backend can name it.
 */
object ReplyFork {

    /**
     * What a reply's footer offers. [forkFromTurnId] is what the fork sends (null forks at the
     * tail); [ready] is false while the point waits on a transcript name it will get (a reply
     * this client folded in itself, before the reparse backfills it — web `isForkPointUnnamed`).
     */
    data class Offer(val forkFromTurnId: String?, val ready: Boolean)

    /**
     * The fork [agent]'s reply at [turnId] (its last turn) offers, or null when it offers none —
     * anywhere a tail fork would land somewhere other than this reply:
     * - an agent that can't fork at a chosen message at all ([canForkAtTurn]);
     * - a turn with no text of its own (a reply that ended on a tool call): Codex names a point
     *   only by its text, Claude Code and DeepSeek by that or the agent's message id, which this
     *   client never sees ([hasForkableText]).
     * At the thread's tail ([atTail]) every reply may fork, and names nothing the backend would
     * have to look up when it couldn't anyway.
     */
    fun offer(turnId: String?, atTail: Boolean, hasText: Boolean, agent: AgentType): Offer? {
        if (turnId == null) return null
        val local = isLocalTurnId(turnId)
        val pointable = canForkAtTurn(agent)
        if (atTail) return Offer(forkFromTurnId = turnId.takeUnless { local || !pointable }, ready = true)
        if (!pointable || !hasText) return null
        return Offer(turnId, ready = !local)
    }

    /** The agents whose `session/fork` honours a chosen message (backend `resolve_fork_point`). */
    fun canForkAtTurn(agent: AgentType): Boolean =
        agent.builtin == BuiltinAgent.CLAUDE_CODE || agent.builtin == BuiltinAgent.CODEX || agent.builtin == BuiltinAgent.DEEPSEEK

    /**
     * Ids this client minted for a reply: `live-…` for one it streamed and folded in, `bg-…` for
     * one read off the agent's transcript out of turn. They name nothing server-side.
     */
    fun isLocalTurnId(id: String): Boolean = id.startsWith("live-") || id.startsWith("bg-")

    /** Whether [turn] has the text a fork point is fingerprinted from (backend `turn_text`, not blank). */
    fun hasForkableText(turn: MessageTurn): Boolean =
        turn.blocks.joinToString("") { (it as? ContentBlock.Text)?.text.orEmpty() }.isNotBlank()

    /**
     * Whether a read after the fork shows it: the row points at the forked session, and its
     * transcript is there. The agent writes the forked transcript itself, so the first read can
     * come back before the file does — empty — and is then tried again.
     */
    fun isApplied(detail: ConversationDetail, forkedSessionId: String): Boolean =
        detail.summary.externalId == forkedSessionId && (detail.turnsTotal ?: detail.turns.size) > 0

    /** The agent has no `session/fork` ("This agent does not support session/fork"): fixed for it. */
    fun isUnsupported(error: Throwable): Boolean =
        (error as? ApiError.Server)?.serverMessage?.contains("does not support session/fork", ignoreCase = true) == true

    /** How many reads after a fork, and how long between them (400 ms, 800 ms, …: about 6 s in all). */
    const val RELOAD_ATTEMPTS = 6
    fun reloadDelayMs(attempt: Int): Long = 400L * (attempt + 1)
}
