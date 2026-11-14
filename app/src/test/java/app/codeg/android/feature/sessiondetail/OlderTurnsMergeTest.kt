package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ConversationDetail
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.ConversationTurnsPage
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnWindowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Joining a page of older history onto the loaded window is the one place two
 * separately-parsed slices of a transcript are spliced together. Turn ids are
 * index-derived, so a page always LOOKS adjacent — only the seam fingerprint can
 * tell a genuine neighbour from a transcript that was renumbered in between.
 */
class OlderTurnsMergeTest {

    private fun turn(id: String, role: TurnRole = TurnRole.USER) =
        MessageTurn(id = id, role = role, timestamp = Instant.EPOCH)

    /** The window the client held when it asked for the page. */
    private val requested = TurnWindowState(offset = 120, total = 400, prefixHash = "aaaa0000aaaa0000")

    private val loaded = listOf(turn("turn-120"), turn("turn-121"))

    private val summary = ConversationSummary(
        id = 1,
        folderId = 2,
        agentType = AgentType.CLAUDE_CODE,
        status = ConversationStatus.COMPLETED,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun page(
        turns: List<MessageTurn> = listOf(turn("turn-118"), turn("turn-119")),
        offset: Int = 118,
        total: Int = 400,
        prefixHash: String = "bbbb1111bbbb1111",
        seam: String = requested.prefixHash,
    ) = ConversationTurnsPage(
        turns = turns,
        turnsOffset = offset,
        turnsTotal = total,
        prefixHash = prefixHash,
        prefixHashBeforeIndex = seam,
    )

    @Test
    fun `a page whose seam matches is prepended and moves the window back`() {
        val merge = mergeOlderTurns(page(), requested, requested, loaded)

        val prepend = merge as PageMerge.Prepend
        assertEquals(listOf("turn-118", "turn-119"), prepend.turns.map { it.id })
        assertEquals(118, prepend.window.offset)
        assertEquals("bbbb1111bbbb1111", prepend.window.prefixHash)
    }

    @Test
    fun `the window keeps its own total, not the page's`() {
        // Both come from the same parse, but the page did not refresh the loaded
        // tail — and total only drives the has-older check, so the fresher one wins.
        val merge = mergeOlderTurns(page(total = 399), requested, requested, loaded)

        assertEquals(400, (merge as PageMerge.Prepend).window.total)
    }

    @Test
    fun `a broken seam asks for a resync instead of splicing`() {
        // A compaction between the two requests renumbers every turn: the page and
        // the loaded window now describe different transcripts, and the ids would
        // still line up perfectly.
        val merge = mergeOlderTurns(page(seam = "ffff9999ffff9999"), requested, requested, loaded)

        assertEquals(PageMerge.Resync, merge)
    }

    @Test
    fun `a window replaced while the page was in flight drops the page`() {
        // NOT a resync: the newer window is authoritative and already correct, so
        // re-fetching it would only throw away good state.
        val moved = requested.copy(offset = 60, prefixHash = "cccc2222cccc2222")
        assertEquals(PageMerge.Drop, mergeOlderTurns(page(), requested, moved, loaded))
        assertEquals(PageMerge.Drop, mergeOlderTurns(page(), requested, null, loaded))
    }

    @Test
    fun `a replaced window wins over a broken seam`() {
        // Both wrong at once: the seam is stale BECAUSE the window it was proved
        // against is gone. Resyncing would refetch a window that is already current.
        val moved = requested.copy(offset = 60, prefixHash = "cccc2222cccc2222")

        assertEquals(
            PageMerge.Drop,
            mergeOlderTurns(page(seam = "ffff9999ffff9999"), requested, moved, loaded),
        )
    }

    @Test
    fun `a page that does not start above the window is dropped`() {
        assertEquals(PageMerge.Drop, mergeOlderTurns(page(offset = 120), requested, requested, loaded))
        assertEquals(PageMerge.Drop, mergeOlderTurns(page(offset = 200), requested, requested, loaded))
    }

    @Test
    fun `turns already on screen are not duplicated at the seam`() {
        val overlapping = page(turns = listOf(turn("turn-118"), turn("turn-120")))

        val prepend = mergeOlderTurns(overlapping, requested, requested, loaded) as PageMerge.Prepend
        assertEquals(listOf("turn-118"), prepend.turns.map { it.id })
    }

    @Test
    fun `the progress baseline counts the whole transcript, not the loaded part`() {
        // `reconcileAfterTurn` compares this against the count captured when the turn
        // started. Reading the loaded length instead would call a reply "landed" the
        // moment a page was prepended, and miss one whenever the window slid.
        val windowed = SessionDetailUiState(isNew = false, turns = loaded, window = requested)
        assertEquals(400, windowed.globalTurnCount)

        // Legacy server: the loaded list IS the transcript.
        assertEquals(2, SessionDetailUiState(isNew = false, turns = loaded).globalTurnCount)
    }

    @Test
    fun `the baseline never trails the loaded window`() {
        // A total the server reported before the window last grew must not hand the next
        // turn a baseline the loaded transcript has already passed.
        val promoted = SessionDetailUiState(
            isNew = false,
            // Window ends at 120 + 2 = 122, but the server last said 120.
            turns = loaded,
            window = TurnWindowState(offset = 120, total = 120, prefixHash = "aaaa0000aaaa0000"),
        )

        assertEquals(122, promoted.globalTurnCount)
    }

    @Test
    fun `a refresh is only trusted when both coordinates still line up`() {
        fun refresh(offset: Int?, total: Int?, hash: String?) = ConversationDetail(
            summary = summary,
            turnsOffset = offset,
            turnsTotal = total,
            prefixHash = hash,
        )

        assertTrue(windowRefreshMatches(refresh(120, 400, "aaaa0000aaaa0000"), requested))
        // Prefix rewritten between the two requests: the offset now names a
        // different turn, so replacing the loaded turns with this would splice.
        assertFalse(windowRefreshMatches(refresh(120, 400, "ffff9999ffff9999"), requested))
        // Transcript shrank past the offset — `FromIndex` is clamped server-side, so
        // the answer is from a coordinate nobody asked about.
        assertFalse(windowRefreshMatches(refresh(80, 80, "aaaa0000aaaa0000"), requested))
        // A server that stopped windowing mid-session: re-take the tail rather than
        // assume the full transcript lines up with the window.
        assertFalse(windowRefreshMatches(refresh(null, null, null), requested))
    }

    @Test
    fun `a page that adds nothing leaves the transcript alone`() {
        // Dropping rather than prepending an empty list matters: a Prepend would
        // still bump the prepend epoch and make the transcript re-anchor its
        // viewport for no reason.
        assertEquals(PageMerge.Drop, mergeOlderTurns(page(turns = emptyList()), requested, requested, loaded))
        assertEquals(
            PageMerge.Drop,
            mergeOlderTurns(page(turns = listOf(turn("turn-120"))), requested, requested, loaded),
        )
    }
}
