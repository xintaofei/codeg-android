package app.codeg.android.feature.sessiondetail.interactive

/**
 * Whether the blocking question card is folded down to its title row. Mirrors codeg 0.44 web's
 * `collapsed` state in `ask-question-card.tsx` (ported via iOS `AskQuestionFold`):
 *
 * - The fold belongs to one question set. A replacement set (a different question id) is a NEW
 *   blocking request; left folded it would read as the same title row the user already put
 *   away, and the agent would sit stalled on an answer nobody knows it asked for. So a new id
 *   always opens — from its very first frame, because the fold is keyed by the id it folded.
 * - A failed submit opens the card again, so the error and the retry are where the user can see
 *   them. On this client that falls out of the answer path: the card is cleared optimistically
 *   and put back on failure, so it comes back as a fresh, open card. [submitFailed] states the
 *   rule for a card that ever survives the round-trip.
 *
 * Selections are not part of this: folding only hides the body, so picks survive a fold/unfold
 * round-trip.
 */
internal data class AskQuestionFold(
    /** The question set the user folded; null while open. */
    private val foldedQuestionId: String? = null,
) {
    fun isCollapsed(questionId: String): Boolean = foldedQuestionId == questionId

    /** The header toggle. */
    fun toggle(questionId: String): AskQuestionFold =
        AskQuestionFold(if (isCollapsed(questionId)) null else questionId)

    /** A question set rendered into this card. A different one clears the fold for good, so the
     *  old set coming back later does not resurface folded. */
    fun present(questionId: String): AskQuestionFold =
        if (foldedQuestionId != questionId) AskQuestionFold(null) else this

    /** The answer (or skip) round-trip failed: open back up. */
    fun submitFailed(): AskQuestionFold = AskQuestionFold(null)
}
