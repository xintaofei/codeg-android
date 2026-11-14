package app.codeg.android.core.model

import app.codeg.android.core.model.wire.stringOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A mid-turn steering note the user sent to a working agent (Rust `FeedbackItem`).
 *
 * Notes are turn-scoped and live only in the server's in-memory session state — they are
 * real-time steering, not transcript history, so they are never persisted and the next
 * turn's `user_message` clears them.
 *
 * Two delivery channels produce these, and the difference shows up right here in [status]:
 * the native ACP `_session/steering` push returns a note that is [DELIVERED] from birth
 * (the adapter already consumed the text), while the `check_user_feedback` MCP pull tool
 * returns [PENDING] and flips later via `feedback_consumed`. The client never picks the
 * channel; the server does.
 */
@Serializable
data class FeedbackNote(
    val id: String = "",
    val text: String = "",
    /** RFC3339, server clock. Kept as the raw string — nothing renders it (see
     *  `FeedbackNotesStrip`), and list order comes from arrival, not from parsing this. */
    val createdAt: String = "",
    val status: String = PENDING,
) {
    /**
     * Whether the note has reached the agent.
     *
     * Deliberately not "the agent read it": what the server actually knows is that the note
     * left its hands — the tool response was written to the MCP companion, or the adapter
     * consumed the steering push. There is still a hop after that, so the surface says
     * "received", not "read".
     *
     * Only the exact [DELIVERED] status counts. A status this build doesn't know reads as
     * "not yet" — claiming delivery we can't confirm would invite the user to stop waiting
     * for a correction that never landed.
     */
    val isDelivered: Boolean get() = status == DELIVERED

    companion object {
        const val PENDING = "pending"
        const val DELIVERED = "delivered"

        fun fromWire(obj: JsonObject): FeedbackNote = FeedbackNote(
            id = obj.stringOrNull("id").orEmpty(),
            text = obj.stringOrNull("text").orEmpty(),
            createdAt = obj.stringOrNull("created_at").orEmpty(),
            status = obj.stringOrNull("status") ?: PENDING,
        )
    }
}
