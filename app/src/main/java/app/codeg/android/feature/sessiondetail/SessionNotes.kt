package app.codeg.android.feature.sessiondetail

import app.codeg.android.R
import app.codeg.android.core.model.FeedbackNote
import app.codeg.android.core.model.LiveSessionSnapshot
import app.codeg.android.core.model.ConnectionStatus
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import app.codeg.android.core.network.isFeedbackDisabled
import app.codeg.android.core.network.isNoActiveTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mid-turn steering notes for one session screen: the channel (whether a note can be sent,
 * and whether it lands at once), the note strip, and the submit.
 *
 * Owns its state and writes only the note fields of [ui] (`feedbackNotes`, `supportsNotes`,
 * `notesLandInstantly`, `noteTurnOpen`, `sendingNote`, plus a `notice` for a refused note).
 * The session tells it about turns ([clear] on a new one, [restore] on an attach snapshot)
 * and forwards the feedback events ([onSubmitted], [onConsumed]).
 */
internal class SessionNotes(
    private val scope: CoroutineScope,
    private val ui: MutableStateFlow<SessionDetailUiState>,
    private val client: () -> CodegClient?,
    private val connectionId: () -> String?,
    private val strings: (Int) -> String,
) {
    /** The [sync] read in flight; a newer one supersedes it. */
    private var noteChannelJob: Job? = null
    /**
     * What the session can do, and which connection that was observed on. Null when nothing
     * has been observed yet, or the observation failed.
     *
     * Held apart from the policy below and from the revocation latch, because all three
     * arrive from different places at different times and only their COMBINATION is what the
     * composer shows ([publishNoteChannel]). Keeping them separate is what lets each be
     * corrected without the correction reaching into the other two.
     */
    private var noteCapability: NoteCapability? = null
    /**
     * The global live-feedback setting, as of the last read that completed.
     *
     * Held apart from the session's capability because the two answer different questions
     * from different places: the capability says whether this agent CAN take a note (the
     * session snapshot knows, and so does every attach frame), while this says whether the
     * user wants to be offered one at all (only the settings endpoint knows). Folding them
     * into one flag is what made an attach frame unable to correct a capability read that
     * lost the race with `initialize` — it had no way to re-supply the half it doesn't carry.
     *
     * Null means unknown — never read, or the last read failed — and nothing is offered
     * until it is known. Fail-closed in both directions: this flag exists to switch the
     * feature OFF, so a dropped request must not be what keeps it on. The cost is one turn
     * without the affordance after a network blip, recovered by the next send's read.
     */
    private var noteFeatureEnabled: Boolean? = null
    /**
     * Tie-break between the two things that observe [noteCapability] — the HTTP read in
     * [sync] and the attach frames in [restore].
     *
     * Neither is provably newer: both just read the server's session state, and arrival
     * order doesn't establish capture order. The attach frame is given the tie because it
     * also replaces the note LIST, and the list and the flags have to describe the same
     * observation. The tie-break is cheap to get wrong in only one direction — a stale
     * `false` — which the `prompting` self-heal re-asks about, while the one transition that
     * is genuinely one-way (native revocation) is latched locally rather than left to this.
     */
    private var noteChannelObservation = 0L
    /**
     * The connection whose native steering the server revoked mid-session, latched.
     *
     * Once revoked it never comes back for that connection, but a read ISSUED before the
     * revoke can still resolve after it and report the channel as live again. Without the
     * latch that stale answer re-advertises instant delivery for a session that no longer
     * has it. Keyed by connection, so a new one starts clean with no reset.
     *
     * Because it is one-way, only an UNAMBIGUOUS proof may write it — a wrong latch strands
     * a working session off permanently. In practice that means the three submit-anchored
     * proofs ([confirmNoteChannel], the `FeedbackDisabled` refusal, and a `pending` item on a
     * session we were calling instant): each follows a note that actually reached the server,
     * so the connection is provably past `initialize` and a reported `false` can only mean
     * revoked. An attach frame does NOT qualify — see [restore].
     */
    private var nativeSteeringRevoked: String? = null
    /** True while a note submit is awaiting the server. */
    private var noteInFlight = false
    /**
     * Notes the agent has read, by id — kept even for notes we never held.
     *
     * A `feedback_consumed` can arrive while the snapshot that would introduce its note is
     * still in flight (attaching mid-turn between the submit and the consume). The event
     * finds nothing to flip, and the older snapshot then reports the note `pending` — so it
     * would sit there "waiting" forever. Cleared with the notes on every new turn.
     */
    private val consumedNoteIds = mutableSetOf<String>()
    /**
     * Bumped by every [clear], i.e. on every new turn.
     *
     * Notes are turn-scoped but the requests that produce them are not: a submit or a
     * hydrating snapshot is an independent HTTP call that can still be in flight when the
     * turn ends and the next one starts. Without this, its response re-adds a note the new
     * turn already dropped — and the connection id, which is reused across turns, can't tell
     * the difference.
     */
    private var noteTurnGeneration = 0


    /**
     * Send a short note to the agent while it is still working.
     *
     * Cooperative, not an interrupt: the server picks the channel per session — either the
     * native ACP `_session/steering` push, which lands in the running turn at once, or the
     * `check_user_feedback` MCP tool, which the agent reads whenever it next looks. Either
     * way the note is a side channel, so the turn keeps running and nothing blocks on it.
     *
     * Resolves true only once the server has the note, which is the composer's cue to clear.
     * Nothing is cleared optimistically: a note is a sentence the user just typed at a
     * running agent, and the alternative — clear on tap, put it back on failure — can't
     * actually put it back if they started typing again in the meantime. The work runs in
     * [viewModelScope], so leaving the screen mid-submit abandons the await, not the note.
     */
    fun send(text: String): Deferred<Boolean> {
        val trimmed = text.trim()
        val c = client()
        val conn = connectionId()
        if (trimmed.isEmpty() || noteInFlight || c == null || conn == null) {
            return CompletableDeferred(false)
        }
        val generation = noteTurnGeneration
        noteInFlight = true
        ui.update { it.copy(sendingNote = true, notice = null) }
        return scope.async {
            try {
                // Shielded: once the user taps send, the note goes. Backing out of the screen
                // clears this VM and would otherwise cancel the request mid-flight, leaving
                // the note neither sent nor recoverable — the composer that held it is gone
                // with the composition. (The server shields only its own native half.)
                val note = withContext(NonCancellable) { c.submitSessionFeedback(conn, trimmed) }
                // A result that lands after we left the connection describes a session that
                // is no longer on screen; the channel facts below belong to that one.
                if (connectionId() != conn) return@async true
                // Read now, not at submit time: something else may have already revoked the
                // channel while this was in flight, and then there is nothing left to detect.
                val believedInstant = ui.value.notesLandInstantly
                val downgraded = believedInstant && !note.isDelivered
                // Latch before publishing: a `pending` item on a native session is proof the
                // server already revoked the channel, and a capability read still in flight
                // from before that would otherwise put "instant" back.
                if (downgraded) revokeNativeSteering(conn)
                ui.update { s ->
                    s.copy(
                        // The flags themselves are not touched here: `revokeNativeSteering`
                        // above already republished them from the latch. This only says so
                        // to the user.
                        notice = if (downgraded) {
                            strings(R.string.feedback_not_instant)
                        } else {
                            s.notice
                        },
                        // The note belongs to the turn it was written for. A slow submit that
                        // returns after the next turn began would otherwise put it back on a
                        // list the new turn already emptied.
                        feedbackNotes = if (generation != noteTurnGeneration ||
                            s.feedbackNotes.any { it.id == note.id }
                        ) {
                            s.feedbackNotes
                        } else {
                            s.feedbackNotes + resolveNote(note)
                        },
                    )
                }
                // `delivered` on a native session is ambiguous: a healthy injection and the
                // round that REVOKED the channel both report it (the detached turn consumed
                // the text, and the flag went false on the way out). Status can't separate
                // them, so ask the one thing that can — off the critical path, since this
                // note itself already landed either way.
                if (believedInstant && note.isDelivered) {
                    scope.launch { confirmNoteChannel(conn) }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val turnEnded = e is ApiError && e.isNoActiveTurn
                // The session lost its last channel — a native-only session whose adapter
                // mishandled the push gets downgraded server-side and then has nothing left,
                // so it refuses outright rather than quietly rerouting. Take the affordance
                // away; forgetting the connection lets the next sync re-derive it honestly.
                if (e is ApiError && e.isFeedbackDisabled) {
                    // The server just said this session has NO channel, which outranks any
                    // capability we hold for it; drop that and let the latch keep native off
                    // if a read still in flight tries to bring it back.
                    noteCapability = null
                    revokeNativeSteering(conn)
                }
                ui.update {
                    it.copy(
                        // The turn-end race is NOT rerouted as an ordinary prompt the way the
                        // web reroutes it into its message queue: `send` refuses while
                        // `isInFlight` is set and our `turn_complete` may not have arrived
                        // yet, so the reroute would silently drop the note in exactly the
                        // race it is meant to cover. The draft is still in the composer —
                        // one tap on Send does it, with the user's eyes on it.
                        notice = if (turnEnded) {
                            strings(R.string.feedback_turn_ended)
                        } else {
                            e.displayMessage()
                        },
                    )
                }
                false
            } finally {
                noteInFlight = false
                ui.update { it.copy(sendingNote = false) }
            }
        }
    }

    /**
     * Re-read the authoritative channel flags after a native note came back delivered.
     *
     * Silent on purpose. That round's note WAS consumed by the agent, so there is nothing to
     * warn about; all that changed is which channel the NEXT one will ride, and the copy
     * follows the flag. Only ever narrows what we offer — this is called for a session we
     * already believed had a channel.
     */
    private suspend fun confirmNoteChannel(conn: String) {
        val snap = runCatching { client()?.sessionSnapshot(conn) }.getOrNull() ?: return
        if (connectionId() != conn || snap.nativeSteeringAvailable) return
        revokeNativeSteering(conn)
    }

    /** Latch a connection's native channel as gone, and republish without it.
     *  @see nativeSteeringRevoked */
    private fun revokeNativeSteering(conn: String) {
        nativeSteeringRevoked = conn
        publishNoteChannel()
    }

    /**
     * Recompute what the composer shows from the three facts that bear on it.
     *
     * The single writer of both flags. Each fact has its own writer and its own ordering —
     * the policy is serialized by [noteChannelJob], the capability by [noteChannelObservation],
     * the revocation is a one-way latch — and none of them may publish alone: an earlier
     * version of this let an attach frame, which carries no policy, discard a settings read
     * that had just come back saying the feature was switched off.
     */
    private fun publishNoteChannel() {
        val capability = noteCapability
        val channel = noteChannel(
            featureEnabled = noteFeatureEnabled == true,
            toolAvailable = capability?.toolAvailable == true,
            nativeAvailable = capability?.nativeAvailable == true,
            nativeRevoked = capability?.connectionId != null &&
                capability.connectionId == nativeSteeringRevoked,
        )
        ui.update {
            it.copy(supportsNotes = channel.supported, notesLandInstantly = channel.instant)
        }
    }

    /**
     * Read whether this connection takes notes, and on which channel.
     *
     * Runs once per connection, when a turn starts on it, because that is the cadence at
     * which the answer can change: `feedback_tool_available` is decided at spawn and can't be
     * retrofitted by enabling the setting later. Native steering is the exception — the
     * server can revoke it mid-session — which is what [confirmNoteChannel] and the
     * `FeedbackDisabled` refusal in [send] are for.
     *
     * With [hydrate], the notes already on the running turn come along: attaching to a turn
     * another client started means its one-shot `feedback_submitted` events fired before this
     * client was listening.
     */
    fun sync(conn: String, hydrate: Boolean) {
        noteChannelJob?.cancel()
        val generation = noteTurnGeneration
        val observation = noteChannelObservation
        // A different connection's capability says nothing about this one. Drop it up front
        // so a session that can't take notes never briefly offers to send one. (Re-syncing
        // the SAME connection keeps its answer, so the affordance doesn't blink each turn.)
        if (noteCapability?.connectionId != conn) {
            noteCapability = null
            publishNoteChannel()
        }
        noteChannelJob = scope.launch {
            val c = client() ?: return@launch
            // The setting is re-read per connection rather than cached for the VM's life
            // because it is the user's CURRENT wish, and the settings screen it lives on can
            // be visited between two turns of this very session. (What the setting was at
            // spawn is a different question, and the snapshot's `feedbackToolAvailable`
            // already answers it.) Both reads go out together — neither depends on the other.
            val answers = coroutineScope {
                val enabled = async { runCatching { c.getFeedbackEnabled() }.getOrNull() }
                val snap = async { runCatching { c.sessionSnapshot(conn) }.getOrNull() }
                enabled.await() to snap.await()
            }
            if (connectionId() != conn) return@launch
            // The policy lands unconditionally. Nothing else reads the setting — an attach
            // frame doesn't carry it — so the capability tie-break below has no business
            // discarding it, and discarding it is how a "the user just switched this off"
            // answer got thrown away.
            //
            // A failed read means unknown, not "as before": the setting's job is to turn the
            // feature off, so a dropped request must never be what keeps it on.
            noteFeatureEnabled = answers.first
            val snapshot = answers.second
            // The capability half defers to an attach frame that landed while this was in
            // flight — that frame also replaced the note list, and the two must agree.
            if (observation == noteChannelObservation) {
                noteCapability = snapshot?.let {
                    NoteCapability(conn, it.feedbackToolAvailable, it.nativeSteeringAvailable)
                }
            }
            publishNoteChannel()
            // A snapshot taken before the turn boundary holds the previous turn's notes,
            // which the server has since cleared and so have we.
            if (hydrate && generation == noteTurnGeneration) {
                ui.update { it.copy(feedbackNotes = mergeNotes(snapshot?.feedback.orEmpty(), it.feedbackNotes)) }
            }
        }
    }

    /** Apply what the consume events know to a note that may predate them. @see consumedNoteIds */
    private fun resolveNote(note: FeedbackNote): FeedbackNote = note.resolvedWith(consumedNoteIds)

    private fun mergeNotes(hydrated: List<FeedbackNote>, live: List<FeedbackNote>): List<FeedbackNote> =
        mergeFeedbackNotes(hydrated, live, consumedNoteIds)

    /** Drop the turn's notes, as the server does on every new `user_message`. */
    fun clear() {
        resetNoteBaseline()
        if (ui.value.feedbackNotes.isNotEmpty()) ui.update { it.copy(feedbackNotes = emptyList()) }
    }

    /**
     * Declare everything known about the turn's notes superseded.
     *
     * Bumping the generation is what makes a clear or an authoritative replace STICK: the
     * requests that produce notes are independent of the events that scope them, so a submit
     * or hydrate issued against the old baseline can still be in flight, and its result would
     * otherwise land on top. The tombstones go with it — they describe ids from a set that no
     * longer exists, and any consume still relevant re-arrives after the frame that reset us.
     */
    private fun resetNoteBaseline() {
        noteTurnGeneration += 1
        consumedNoteIds.clear()
    }


    /**
     * Reconcile the note strip from an attach snapshot, which REPLACES what we held.
     *
     * This is the only recovery when a reconnect's gap is too wide for the server to replay:
     * the `feedback_submitted` / `feedback_consumed` events it covers are never re-sent. That
     * is also why it can't merge — an empty list means the turn has no notes, and a gap wide
     * enough to swallow the `user_message` boundary would otherwise leave the PREVIOUS turn's
     * notes on screen forever. Anything the snapshot predates arrives as an event after it,
     * since the server orders the stream behind the frame.
     *
     * The frame also wins the capability tie-break against a channel read still in flight —
     * not because it is provably newer (it isn't; see [noteChannelObservation]) but because
     * it replaces the note LIST here, and the list and the flags have to describe the same
     * observation.
     */
    fun restore(snap: LiveSessionSnapshot) {
        noteChannelObservation += 1
        val conn = snap.connectionId ?: connectionId()
        // Deliberately does NOT write the revocation latch, even though a frame reporting no
        // native channel where we had seen one looks like proof. It isn't: this frame and the
        // HTTP read are captured independently, so a frame taken before a fresh agent's
        // `initialize` can arrive AFTER a read that already saw the channel — and its `false`
        // means "not written yet", not "revoked". Latching on that would strand a capable
        // connection off for good, since the latch is one-way.
        //
        // The frame still turns the channel off through the capability below, which is not
        // sticky. Only the three submit-anchored proofs latch, and each of them follows a
        // note actually reaching the server, so the connection is provably initialized.
        // Replacing the list is replacing the baseline: an in-flight submit or hydrate from
        // before this frame describes notes it has already ruled on, and appending its result
        // afterwards would undo the correction.
        resetNoteBaseline()
        // The frame supplies the CAPABILITY half only. Whether to offer notes at all also
        // depends on the global setting, which this frame doesn't carry — so it updates its
        // half and lets `publishNoteChannel` recombine. That is what lets a frame correct a
        // capability read that lost the race with `initialize`: a reconnect falling back to
        // a snapshot (a wide enough event gap, or one oversized event clearing the replay
        // ring) is a routine way to miss the `prompting` self-heal.
        noteCapability = NoteCapability(conn, snap.feedbackToolAvailable, snap.nativeSteeringAvailable)
        publishNoteChannel()
        ui.update {
            it.copy(
                feedbackNotes = snap.feedback.map { n -> resolveNote(n) },
                // Attaching to a turn another client started means the `user_message` that
                // would have opened the channel is long past, and `status_changed` only fires
                // on a CHANGE — so without the frame's own state a reattached turn could
                // never be steered. `prompting` alone isn't enough: the loop reports it
                // before emitting `user_message`, and that event is what clears the notes.
                noteTurnOpen = snap.status == ConnectionStatus.PROMPTING && snap.hasPendingUserMessage,
            )
        }
    }

    /** Any client's note, including the echo of one this client just sent — deduped by id
     *  against the note the submit call already returned. */
    fun onSubmitted(note: FeedbackNote) {
        ui.update {
            if (it.feedbackNotes.any { n -> n.id == note.id }) it
            else it.copy(feedbackNotes = it.feedbackNotes + resolveNote(note))
        }
    }

    /** The agent read these notes. Remembered by id even for notes not held yet: see
     *  [consumedNoteIds]. */
    fun onConsumed(ids: List<String>) {
        consumedNoteIds += ids
        ui.update { it.copy(feedbackNotes = it.feedbackNotes.map(::resolveNote)) }
    }
}
