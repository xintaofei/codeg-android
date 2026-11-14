package app.codeg.android.feature.sessiondetail.rendering

/**
 * codex Plan-mode `<proposed_plan>…</proposed_plan>` blocks, lifted out of a reply's text into
 * cards of their own (codeg web 0.44 `expandProposedPlanText` in `ai-elements-adapter.ts`).
 * Live and history both hand raw text to the same path, so they converge.
 */
object ProposedPlan {

    private const val OPEN = "<proposed_plan>"
    private const val CLOSE = "</proposed_plan>"
    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")

    /**
     * [text] split into prose and [RenderPart.ProposedPlan] parts, or null when it holds no
     * block — including when every tag in it is only quoted as code, so such a message renders
     * verbatim. An opener counts only at the start of its own line (codex writes the block as
     * its own record; a mid-line one is codex naming the tag in prose). While [streaming], an
     * unclosed block is a card being written; the tags never render either way.
     */
    fun expand(text: String, streaming: Boolean): List<RenderPart>? {
        if (!text.contains(OPEN)) return null
        val codeSpans = codeSpans(text)
        val parts = mutableListOf<RenderPart>()
        var cursor = 0
        var sawPlan = false
        while (true) {
            var open = indexOutsideCode(text, OPEN, cursor, codeSpans)
            while (open != -1 && !opensOwnLine(text, open)) open = indexOutsideCode(text, OPEN, open + OPEN.length, codeSpans)
            if (open == -1) break
            sawPlan = true
            val lead = text.substring(cursor, open)
            if (lead.isNotBlank()) parts.add(RenderPart.Text(lead))
            val bodyStart = open + OPEN.length
            // The closer isn't held to the line-start rule: a closing tag gone unrecognised
            // would drag the trailing prose into the card, worse than a card ending early.
            val close = indexOutsideCode(text, CLOSE, bodyStart, codeSpans)
            val unclosed = close == -1
            val body = (if (unclosed) text.substring(bodyStart) else text.substring(bodyStart, close)).trim()
            val writing = unclosed && streaming
            if (body.isNotEmpty() || writing) parts.add(RenderPart.ProposedPlan(body, writing))
            if (unclosed) {
                cursor = text.length
                break
            }
            cursor = close + CLOSE.length
        }
        val trail = text.substring(cursor)
        if (trail.isNotBlank()) parts.add(RenderPart.Text(trail))
        return if (sawPlan) parts else null
    }

    /** The next [marker] at or after [from] that isn't quoted as code, or -1. */
    private fun indexOutsideCode(text: String, marker: String, from: Int, spans: List<IntRange>): Int {
        var at = text.indexOf(marker, from)
        while (at != -1 && spans.any { at in it }) at = text.indexOf(marker, at + marker.length)
        return at
    }

    /** Whether the opener at [index] begins its own line, within CommonMark's three-space indent. */
    private fun opensOwnLine(text: String, index: Int): Boolean {
        val lead = text.substring(text.lastIndexOf('\n', index - 1) + 1, index)
        if (lead.isNotBlank()) return false
        return lead.replace("\t", "    ").length <= 3
    }

    /**
     * The ranges of [text] quoted as code: fenced blocks (an unclosed one runs to the end, as
     * the renderer shows it) and inline spans, matched within one line so a stray backtick
     * marks nothing. Web `markdownCodeSpans`.
     */
    private fun codeSpans(text: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var fenceChar: Char? = null
        var fenceLength = 0
        var fenceStart = 0
        var lineStart = 0
        while (true) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline == -1) text.length else newline
            val contentEnd = if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
            val marker = FENCE.find(text.substring(lineStart, contentEnd))
            if (fenceChar != null) {
                if (marker != null) {
                    val run = marker.groupValues[1]
                    if (run[0] == fenceChar && run.length >= fenceLength && marker.groupValues[2].isBlank()) {
                        spans.add(fenceStart until lineEnd)
                        fenceChar = null
                    }
                }
            } else if (marker != null) {
                val run = marker.groupValues[1]
                fenceChar = run[0]
                fenceLength = run.length
                fenceStart = lineStart
            } else {
                inlineSpans(text, lineStart, contentEnd, spans)
            }
            if (newline == -1) break
            lineStart = newline + 1
        }
        if (fenceChar != null) spans.add(fenceStart until text.length)
        return spans
    }

    /** A run of N backticks opens a span only a later run of exactly N closes (CommonMark). */
    private fun inlineSpans(text: String, lineStart: Int, lineEnd: Int, spans: MutableList<IntRange>) {
        fun runEnd(from: Int): Int {
            var end = from
            while (end < lineEnd && text[end] == '`') end++
            return end
        }
        var index = lineStart
        while (index < lineEnd) {
            if (text[index] != '`') {
                index++
                continue
            }
            val openEnd = runEnd(index)
            val width = openEnd - index
            var close = -1
            var scan = openEnd
            while (scan < lineEnd) {
                if (text[scan] != '`') {
                    scan++
                    continue
                }
                val end = runEnd(scan)
                if (end - scan == width) {
                    close = end
                    break
                }
                scan = end
            }
            if (close == -1) {
                index = openEnd
                continue
            }
            spans.add(index until close)
            index = close
        }
    }
}
