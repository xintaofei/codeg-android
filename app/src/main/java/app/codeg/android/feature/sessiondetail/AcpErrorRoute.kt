package app.codeg.android.feature.sessiondetail

/**
 * What an ACP `error` event is, keyed on its stable `code` — port of the web's
 * `acp-error-presentation.ts` and of its `case "error"` handler.
 *
 * The server raises one `error` event for very different things: a turn that failed, an agent
 * process that died, a mode switch the agent refused, an image Grok dropped. None of them ends
 * the turn by itself. The turn ends where it always does — `turn_complete`, or
 * `status_changed: error` when the process dies (`connection.rs` emits the latter right after
 * its one terminal `error`). What an error does besides being told:
 *
 * - A verdict that the turn FAILED, or a prompt the agent rejected (no code: the message is
 *   the agent's own), is the turn's failure line, kept on the running reply.
 * - A failed compaction is already the compaction card's failed state: nothing more.
 * - Anything else — a session problem, the answer to a selector the user just changed, an
 *   unknown code — is a notice. Unknown and absent codes are never demoted below that.
 */
internal object AcpErrorRoute {

    enum class Kind {
        /** This session's state went wrong; the text also names a later process death. */
        SESSION,
        /** The answer to something the user just did (a selector, a goal control, an image). */
        ACTION,
        /** Already shown by a card in the transcript. */
        TRANSCRIPT,
    }

    /** What one `error` event does here. */
    data class Decision(
        val kind: Kind,
        /** The line to show: the agent's own words for a rejected prompt, else the message. */
        val text: String,
        /** Closes the running reply with this failure line (the turn itself runs on). */
        val failsTurn: Boolean,
        /** The failure line is the agent's own account, which outranks codeg's verdict. */
        val fromAgent: Boolean,
    )

    private val KINDS: Map<String, Kind> = mapOf(
        "agent_runtime_outdated" to Kind.SESSION,
        "session_load_fallback" to Kind.SESSION,
        "set_mode_failed" to Kind.ACTION,
        "set_config_option_failed" to Kind.ACTION,
        "grok_model_switch_incompatible_agent" to Kind.ACTION,
        "goal_control_failed" to Kind.ACTION,
        "image_dropped" to Kind.ACTION,
        "compaction_failed" to Kind.TRANSCRIPT,
    )

    /**
     * The verdicts that mean the turn FAILED — nothing came back, or the agent would not run
     * it — as opposed to one that ended early WITH output (`turn_failed_refusal`,
     * `turn_failed_max_tokens`, `turn_failed_max_turn_requests`), which is told, not kept.
     */
    private val FAILED_TURN_VERDICTS: Set<String> = setOf(
        "turn_failed_auth_required",
        "turn_failed_empty",
        "turn_failed_empty_protocol",
        "turn_failed_empty_metadata",
        "turn_failed_unknown",
    )

    private val PROTOCOL_PREFIX = Regex("^ACP protocol error:\\s*")

    fun kind(code: String?): Kind = code?.let { KINDS[it] } ?: Kind.SESSION

    fun isFailedTurnVerdict(code: String?): Boolean = code != null && code in FAILED_TURN_VERDICTS

    /** The agent's own words in a prompt it rejected, without the server's protocol prefix. */
    fun rejectedPromptText(message: String): String = message.replace(PROTOCOL_PREFIX, "")

    fun decide(code: String?, message: String): Decision {
        val kind = kind(code)
        return when {
            isFailedTurnVerdict(code) -> Decision(kind, message, failsTurn = true, fromAgent = false)
            code == null -> Decision(kind, rejectedPromptText(message), failsTurn = true, fromAgent = true)
            else -> Decision(kind, message, failsTurn = false, fromAgent = false)
        }
    }
}
