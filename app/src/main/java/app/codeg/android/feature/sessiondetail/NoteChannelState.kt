package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.FeedbackNote

// Mid-turn steering notes: channel capability and note-status resolution.

/** What this client will offer for a connection: whether a note can be sent at all, and
 *  whether it lands in the running turn rather than waiting to be picked up. */
internal data class NoteChannel(val supported: Boolean, val instant: Boolean)

/** What a session can do, and the connection it was observed on (capabilities are per
 *  agent process, so an answer is worthless once the connection changes). */
internal data class NoteCapability(
    val connectionId: String?,
    val toolAvailable: Boolean,
    val nativeAvailable: Boolean,
)

/**
 * Decide what to offer from the three facts that bear on it, each of which arrives from a
 * different place and at a different time.
 *
 * [featureEnabled] is the user's current wish, and only the settings endpoint has it.
 * [toolAvailable] / [nativeAvailable] are the session's capability, which the snapshot and
 * every attach frame carry. [nativeRevoked] is local memory of a mid-session revocation the
 * server will not report to a read that was already in flight when it happened.
 *
 * Kept in one function because the failure mode is always the same shape: a caller holding
 * one of the three publishes it as if it were the whole answer, and the composer either
 * offers a channel the session doesn't have or withholds one it does.
 */
internal fun noteChannel(
    featureEnabled: Boolean,
    toolAvailable: Boolean,
    nativeAvailable: Boolean,
    nativeRevoked: Boolean,
): NoteChannel {
    val native = nativeAvailable && !nativeRevoked
    return NoteChannel(
        supported = featureEnabled && (toolAvailable || native),
        instant = featureEnabled && native,
    )
}

/**
 * Fold in what the consume events already know, for a note that may predate them.
 *
 * A `feedback_consumed` can land before the note it names is on screen — the snapshot that
 * would introduce it is a separate request, and attaching mid-turn can start it before the
 * consume and resolve it after. Without this the note arrives stamped `pending` and stays
 * "waiting" for a read that already happened.
 *
 * Only ever upgrades. [consumed] is an accumulated set with no expiry inside a turn, so
 * letting it push a note back to pending would flip a delivered note on any re-merge.
 */
internal fun FeedbackNote.resolvedWith(consumed: Set<String>): FeedbackNote =
    if (!isDelivered && id in consumed) copy(status = FeedbackNote.DELIVERED) else this

/**
 * Fold snapshot-hydrated notes into the live ones, taking whichever copy is further along.
 *
 * Not "live wins": neither side is reliably newer. The live list can hold the local echo of
 * a note the server has since delivered, and the snapshot can predate a consume event that
 * already arrived. Delivery is monotonic on the server, so the merge takes the maximum
 * rather than picking a winner — that is true whichever request resolved last.
 *
 * Order comes from arrival, not from the notes' timestamps: the snapshot lists them in
 * submit order, anything live was appended as it came, and a live note the snapshot predates
 * is necessarily newer than all of them. Sorting on `created_at` would be the fragile
 * option — chrono varies the fractional-second precision, so a plain string compare puts
 * `…:07.5Z` before `…:07Z`.
 */
internal fun mergeFeedbackNotes(
    hydrated: List<FeedbackNote>,
    live: List<FeedbackNote>,
    consumed: Set<String>,
): List<FeedbackNote> {
    if (hydrated.isEmpty()) return live
    val byId = LinkedHashMap<String, FeedbackNote>(hydrated.size + live.size)
    hydrated.forEach { byId[it.id] = it.resolvedWith(consumed) }
    live.forEach { note ->
        val resolved = note.resolvedWith(consumed)
        // Re-putting an existing key keeps its original position, so a note both halves hold
        // stays where the snapshot ordered it. [consumed] applies to this half too: it can
        // only upgrade, and one path in beats an invariant that the live list was already
        // resolved on the way in.
        val existing = byId[resolved.id]
        byId[resolved.id] = if (existing != null && existing.isDelivered) existing else resolved
    }
    return byId.values.toList()
}
