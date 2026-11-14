package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.ConversationOutline
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnWindowState
import java.time.Instant

/** One user message in the navigator: what it said, when, and where it is. */
data class MessageNavEntry(
    val turnId: String,
    /** 1-based, across the whole conversation. */
    val ordinal: Int,
    /** The message's first text, cut to [MessageNav.LABEL_MAX]; null for one with no text. */
    val label: String?,
    val sentAt: Instant,
    /** Its global turn index while it sits above the loaded window (the history must be paged
     *  in through it first); null once it is loaded. */
    val turnIndex: Int?,
)

/**
 * The message navigator's list (codeg web 0.44 `buildMessageNavEntries`): one entry per user
 * message, the ones above the loaded window from the outline, numbered across the whole
 * conversation. Pure, for tests.
 */
object MessageNav {

    const val LABEL_MAX = 80

    /**
     * Whether [outline] can stand for the history above [window] (web `outlinePrefix`): it must
     * reach the window, and its first `window.offset` turns must fingerprint to the window's
     * prefix — otherwise the history was rewritten since, and the positions name other turns.
     * The usable prefix, or null.
     */
    fun outlinePrefix(outline: ConversationOutline, window: TurnWindowState): List<MessageTurn>? {
        val offset = window.offset
        if (outline.turns.size < offset) return null
        if (outline.turns.size == offset) return outline.turns.takeIf { outline.prefixHash == window.prefixHash }
        val prefix = outline.turns.subList(0, offset)
        return prefix.takeIf { extendPrefixFingerprint(FNV_SEED_HEX, it) == window.prefixHash }
    }

    /**
     * [above] (the usable outline prefix, global turns `0 until window.offset`) then [loaded] (the
     * window and what this client folded in after it), one entry per user message; a turn listed
     * in both counts once.
     */
    fun entries(above: List<MessageTurn>, loaded: List<MessageTurn>): List<MessageNavEntry> {
        val out = ArrayList<MessageNavEntry>()
        val seen = HashSet<String>()
        fun add(turn: MessageTurn, index: Int?) {
            if (turn.role != TurnRole.USER || !seen.add(turn.id)) return
            out += MessageNavEntry(turn.id, out.size + 1, label(turn), turn.timestamp, index)
        }
        above.forEachIndexed { i, turn -> add(turn, i) }
        loaded.forEach { add(it, null) }
        return out
    }

    /** The first text, trimmed, cut at [LABEL_MAX] (web `extractUserMessage`). */
    fun label(turn: MessageTurn): String? {
        val text = turn.blocks.firstNotNullOfOrNull { (it as? ContentBlock.Text)?.text?.trim()?.takeIf { t -> t.isNotEmpty() } }
            ?: return null
        val line = text.replace(Regex("\\s+"), " ")
        return if (line.length > LABEL_MAX) line.take(LABEL_MAX - 3) + "..." else line
    }
}
