package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.FeedbackNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconciling the two sources a note list is built from: the session snapshot (fetched when
 * attaching to a turn already in progress) and the live event stream.
 *
 * They race. The snapshot is a separate request that can be issued before a `feedback_consumed`
 * and resolve after it, so "which one is right" has to be decided per field, not per source.
 */
class FeedbackNotesMergeTest {

    private fun note(id: String, status: String = FeedbackNote.PENDING) =
        FeedbackNote(id = id, text = "note $id", createdAt = "", status = status)

    private val delivered = FeedbackNote.DELIVERED

    // --- resolvedWith ------------------------------------------------------

    @Test
    fun `a consumed id marks a pending note delivered`() {
        assertTrue(note("f1").resolvedWith(setOf("f1")).isDelivered)
    }

    @Test
    fun `a note nobody consumed is untouched`() {
        val n = note("f1")
        assertEquals(n, n.resolvedWith(setOf("f2")))
    }

    @Test
    fun `an already-delivered note is left alone`() {
        // Identity, not just equality: the upgrade must be a no-op, never a rewrite that
        // could drop a field a later build adds to the note.
        val n = note("f1", delivered)
        assertTrue(n === n.resolvedWith(setOf("f1")))
        assertTrue(n === n.resolvedWith(emptySet()))
    }

    // --- mergeFeedbackNotes ------------------------------------------------

    @Test
    fun `no snapshot notes leaves the live list exactly as it is`() {
        val live = listOf(note("f1"), note("f2"))
        assertTrue(live === mergeFeedbackNotes(emptyList(), live, emptySet()))
    }

    @Test
    fun `a live delivery beats a stale pending snapshot`() {
        // The snapshot was taken before the agent read the note; the live consume arrived
        // first. The older read must not undo it.
        val merged = mergeFeedbackNotes(
            hydrated = listOf(note("f1")),
            live = listOf(note("f1", delivered)),
            consumed = emptySet(),
        )

        assertEquals(1, merged.size)
        assertTrue(merged.single().isDelivered)
    }

    @Test
    fun `a snapshot delivery beats a stale pending live note`() {
        // The other direction, and the reason the merge takes a maximum instead of picking a
        // side: the live list can be holding the local echo of a note the server has since
        // delivered. "Live is newer" is not an invariant — the two are separate requests.
        val merged = mergeFeedbackNotes(
            hydrated = listOf(note("f1", delivered)),
            live = listOf(note("f1")),
            consumed = emptySet(),
        )

        assertTrue(merged.single().isDelivered)
    }

    @Test
    fun `delivery never regresses whichever half is stale`() {
        // Server-side delivery is one-way, so no merge ordering may ever walk it back.
        for (consumed in listOf(emptySet(), setOf("f1"))) {
            assertTrue(
                mergeFeedbackNotes(listOf(note("f1", delivered)), listOf(note("f1")), consumed)
                    .single().isDelivered,
            )
            assertTrue(
                mergeFeedbackNotes(listOf(note("f1")), listOf(note("f1", delivered)), consumed)
                    .single().isDelivered,
            )
        }
    }

    @Test
    fun `a consume that beat the snapshot still marks its note delivered`() {
        // The race this exists for: `feedback_consumed` for f1 arrives while the snapshot is
        // in flight, finds no note to flip, and the snapshot then reports f1 pending. Without
        // the tombstone the row says "waiting" for the rest of the turn.
        val merged = mergeFeedbackNotes(
            hydrated = listOf(note("f1")),
            live = emptyList(),
            consumed = setOf("f1"),
        )

        assertTrue(merged.single().isDelivered)
    }

    @Test
    fun `a note held live keeps the snapshot's position`() {
        // f2 is in both halves. Appending it as if it were new would show the turn's notes
        // out of the order they were written in.
        val merged = mergeFeedbackNotes(
            hydrated = listOf(note("f1"), note("f2"), note("f3")),
            live = listOf(note("f2", delivered)),
            consumed = emptySet(),
        )

        assertEquals(listOf("f1", "f2", "f3"), merged.map { it.id })
        assertTrue(merged[1].isDelivered)
    }

    @Test
    fun `a live-only note lands after the snapshot's`() {
        // Anything live that the snapshot doesn't know about was submitted after the snapshot
        // was taken, so the end is where it belongs.
        val merged = mergeFeedbackNotes(
            hydrated = listOf(note("f1")),
            live = listOf(note("f2")),
            consumed = emptySet(),
        )

        assertEquals(listOf("f1", "f2"), merged.map { it.id })
    }

    @Test
    fun `the tombstone reaches the live half too`() {
        // Both halves go through the same upgrade, so the result doesn't depend on the live
        // list having been resolved on its way in.
        val merged = mergeFeedbackNotes(
            hydrated = listOf(note("f0")),
            live = listOf(note("f1")),
            consumed = setOf("f1"),
        )

        assertFalse(merged.first().isDelivered)
        assertTrue(merged.last().isDelivered)
    }
}
