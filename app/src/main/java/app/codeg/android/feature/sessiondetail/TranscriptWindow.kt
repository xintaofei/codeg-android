package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.ConversationDetail
import app.codeg.android.core.model.ConversationTurnsPage
import app.codeg.android.core.model.TurnWindowState
import app.codeg.android.core.model.MessageTurn

// Windowed transcript paging: refresh matching and older-page merging.

/**
 * Whether a `FromIndex` refresh really describes the window it was asked for —
 * the precondition for replacing the loaded turns with it wholesale instead of
 * merging.
 *
 * Both coordinates are checked. The fingerprint catches a rewritten prefix, the
 * one shape that leaves the offset naming a different turn. The offset catches
 * the other: `FromIndex` is clamped to the transcript length server-side, so a
 * transcript that SHRANK past the offset is answered from a coordinate nobody
 * asked about.
 */
internal fun windowRefreshMatches(fresh: ConversationDetail, loaded: TurnWindowState): Boolean {
    val window = fresh.window ?: return false
    return window.offset == loaded.offset && window.prefixHash == loaded.prefixHash
}

/** What to do with a page of older history — see [mergeOlderTurns]. */
internal sealed interface PageMerge {
    /** Join [turns] onto the front of the loaded transcript and adopt [window]. */
    data class Prepend(val turns: List<MessageTurn>, val window: TurnWindowState) : PageMerge

    /**
     * The page and the loaded window describe different transcripts. Nothing can
     * be salvaged by splicing; the window has to be re-taken from the server.
     */
    data object Resync : PageMerge

    /** Superseded, or the page adds nothing. Leave the transcript alone. */
    data object Drop : PageMerge
}

/**
 * Decide whether a page of older history may join the loaded window.
 *
 * The turn ids in a page are index-derived and the ranges are meant to abut, so
 * "looks adjacent" is not evidence — a compaction between the two requests
 * renumbers everything and would splice a plausible-looking transcript that never
 * existed. The proof is [ConversationTurnsPage.prefixHashBeforeIndex]: computed
 * by the same parse that produced the page, over the turns before the index that
 * was asked for. It must equal the fingerprint the window held at request time
 * ([requested]) — and that window must still be the one loaded ([current]),
 * or a refresh replaced it while the page was in flight.
 *
 * @param loadedTurns the turns already on screen, for the seam de-dup.
 */
internal fun mergeOlderTurns(
    page: ConversationTurnsPage,
    requested: TurnWindowState,
    current: TurnWindowState?,
    loadedTurns: List<MessageTurn>,
): PageMerge {
    // Checked BEFORE the seam: a window swapped under us is not a rewrite — the
    // newer one is authoritative and this page simply describes coordinates nobody
    // is showing, so its seam says nothing about what is loaded now.
    if (current == null || current != requested) return PageMerge.Drop
    if (page.prefixHashBeforeIndex != requested.prefixHash) return PageMerge.Resync
    // A page starting at or after the window is not older history.
    if (page.turnsOffset >= current.offset) return PageMerge.Drop

    // The ranges are disjoint by construction; this only guards a server that
    // answered with more than was asked for.
    val loadedIds = loadedTurns.mapTo(HashSet()) { it.id }
    val older = page.turns.filterNot { it.id in loadedIds }
    if (older.isEmpty()) return PageMerge.Drop

    return PageMerge.Prepend(
        turns = older,
        window = TurnWindowState(
            offset = page.turnsOffset,
            // The window's own total, not the page's: both come from the same parse,
            // but the loaded tail was not refreshed by this request, and total only
            // drives the has-older check.
            total = current.total,
            prefixHash = page.prefixHash,
        ),
    )
}
