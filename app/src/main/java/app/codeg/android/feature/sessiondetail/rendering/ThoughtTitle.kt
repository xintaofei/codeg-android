package app.codeg.android.feature.sessiondetail.rendering

/**
 * A one-line title for a reasoning block, so a folded thought still says what it was about
 * ("Thought · Summarizing log status") instead of a bare "Thought" — port of the web's
 * `src/lib/activity/thought.ts` (codeg 0.44).
 *
 * Models that write reasoning summaries open each section with a heading — a whole line of
 * bold (`**Planning the fix**`) or an ATX heading. While the block is still being written the
 * newest heading is the live topic; once it is done the first one names it, since later
 * headings are steps inside it. Reasoning without headings falls back to its first sentence.
 *
 * The regexes spell out JavaScript's semantics: `.` (no `s` flag) stops only at `\n`, `\r`,
 * U+2028 and U+2029, `$` (no `m` flag) is the end of the input, `\s` is Unicode whitespace.
 * Lengths are UTF-16 units, as the web's are; a clip never splits a surrogate pair.
 */
object ThoughtTitle {

    const val MAX_TITLE_LENGTH = 60

    private const val WS = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
    private const val DOT = "[^\\n\\r\\u2028\\u2029]"

    private val BOLD_LINE = Regex("^$WS*(?:\\*\\*|__)($DOT+?)(?:\\*\\*|__)$WS*:?$WS*\\z")
    private val ATX_HEADING = Regex("^$WS{0,3}#{1,6}$WS+($DOT+?)$WS*#*$WS*\\z")
    private val INDENTED_FENCE = Regex("^$WS*(?:```|~~~)")
    private val FENCE = Regex("^(?:```|~~~)")
    private val SENTENCE_END = Regex("[.!?。！？](?=$WS|\\z)")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val EMPHASIS = Regex("\\*\\*|__")
    private val WS_RUN = Regex("$WS+")
    private val LIST_MARK = Regex("^[-*+>]$WS+")
    private val LEADING_BLANK_LINES = Regex("^$WS*\\n")

    private const val JS_WHITESPACE = "\t\n\u000B\u000C\r \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005" +
        "\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"

    /** JavaScript's `trim`. */
    private fun trim(s: String): String = s.trim { it in JS_WHITESPACE }

    /** The thought's title, or null for an empty one. Web `thoughtTitle`. */
    fun title(content: String, streaming: Boolean): String? {
        if (trim(content).isEmpty()) return null
        val found = headings(content)
        if (found.isNotEmpty()) return clip(if (streaming) found.last() else found.first())
        return firstSentence(content)?.let(::clip)
    }

    /**
     * The thought's body with the heading the title already shows taken off the top, so the
     * open panel does not repeat the row it hangs under. Web `thoughtBodyWithoutTitle`.
     */
    fun bodyWithoutTitle(content: String, title: String?): String {
        if (title.isNullOrEmpty()) return content
        val lines = content.split("\n")
        val first = lines.indexOfFirst { trim(it).isNotEmpty() }
        if (first < 0) return content
        val heading = headingText(lines[first]) ?: return content
        if (clip(cleanInline(heading)) != title) return content
        return lines.drop(first + 1).joinToString("\n").replaceFirst(LEADING_BLANK_LINES, "")
    }

    private fun cleanInline(text: String): String =
        trim(text.replace(INLINE_CODE, "$1").replace(EMPHASIS, "").replace(WS_RUN, " "))

    private fun clip(text: String): String {
        if (text.length <= MAX_TITLE_LENGTH) return text
        var end = MAX_TITLE_LENGTH - 1
        // Never half an astral character.
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end).trimEnd { it in JS_WHITESPACE } + "…"
    }

    /** The text of a whole-line bold or ATX heading, before inline cleanup. */
    private fun headingText(line: String): String? =
        (BOLD_LINE.find(line) ?: ATX_HEADING.find(line))?.groupValues?.get(1)

    private fun headings(content: String): List<String> {
        val found = mutableListOf<String>()
        var inFence = false
        for (line in content.split("\n")) {
            if (INDENTED_FENCE.containsMatchIn(line)) {
                inFence = !inFence
                continue
            }
            if (inFence) continue
            val heading = headingText(line) ?: continue
            val title = cleanInline(heading)
            if (title.isNotEmpty()) found += title
        }
        return found
    }

    private fun firstSentence(content: String): String? {
        val text = content.split("\n").map(::trim).firstOrNull { it.isNotEmpty() && !FENCE.containsMatchIn(it) }
            ?: return null
        val plain = cleanInline(text.replaceFirst(LIST_MARK, ""))
        if (plain.isEmpty()) return null
        val end = SENTENCE_END.find(plain)?.range?.first ?: -1
        return if (end > 0) plain.substring(0, end) else plain
    }
}
