package app.codeg.android.feature.sessiondetail.timeline

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One composition's view of the fold: the state [advanceReplyFold] gives for what it
 * observed, the current round, and an override [carriedFold] hands the round that just gave
 * way — all before [ReplyFolds.commit] stores them. Read in the same composition that
 * observed the change, so a send and the fold it causes land in one frame.
 */
data class FoldView(
    val fold: ReplyFoldState,
    /** The current round's reply id: the newest reply, once the agent started on it. */
    val currentRound: String?,
    val carried: Pair<String, ManualFold>?,
)

/** The thread's fold state, held by the screen (see ReplyFold.kt). */
@Stable
class ReplyFolds {
    private var stored by mutableStateOf(ReplyFoldState())
    private val manual = mutableStateMapOf<String, ManualFold>()

    /** Bumped on every [manual] change, for the keys of what reads it. */
    var version by mutableIntStateOf(0)
        private set

    // Only read by [observe] and written by [commit]; nothing draws from them directly.
    private var lastRound: String? = null
    private var lastRoundEpoch = 0

    /** Apply this composition's [input]; [newestReply] is the thread's newest reply, if any. */
    fun observe(input: ReplyFoldInput, newestReply: String?): FoldView {
        val fold = advanceReplyFold(stored, input)
        val current = if (fold.armed) newestReply else null
        return FoldView(fold, current, carriedFold(fold, lastRound, lastRoundEpoch, current))
    }

    /** Store what [view] derived, after the composition that drew it. */
    fun commit(view: FoldView) {
        if (stored != view.fold) stored = view.fold
        view.carried?.let { (id, fold) ->
            if (manual[id] != fold) {
                manual[id] = fold
                version++
            }
        }
        lastRound = view.currentRound
        lastRoundEpoch = view.fold.epoch
    }

    fun isOpen(view: FoldView, rows: ReplyRows): Boolean {
        val override = view.carried?.takeIf { it.first == rows.id }?.second ?: manual[rows.id]
        return replyOpen(view.fold, rows.id == view.currentRound, override, rows.completed)
    }

    fun setOpen(view: FoldView, replyId: String, open: Boolean) {
        if (replyId == view.currentRound) {
            stored = view.fold.copy(roundOpen = open)
        } else {
            manual[replyId] = ManualFold(view.fold.epoch, open)
            version++
        }
    }
}
