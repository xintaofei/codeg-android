package app.codeg.android.core.designsystem.markdown

/**
 * GFM autolink literals — a bare `https://…` / `www.…` in prose becomes a link —
 * with the trailing-punctuation rules CJK text needs.
 *
 * A url written inside parentheses and followed by a fullwidth stop,
 * `(https://github.com/o/r)。`, is the ordinary shape in Chinese prose, and the
 * `)。` belongs OUTSIDE the link. GFM literals only end at whitespace, `<`, or
 * end of input, so CJK punctuation never terminates one, and the
 * trailing-punctuation rules that would have trimmed the `)` — they run only
 * where the literal ends — never get to fire.
 *
 * Port of the web `remark-cjk-autolink-tail` plugin (itself a port of
 * micromark's `pathInside` / `tokenizeTrail` states), restructured because the
 * two run at different layers:
 *
 *  - the web plugin runs AFTER micromark has tokenized and trimmed, so it may
 *    re-apply [autolinkEnd] only where a CJK cut re-exposed punctuation — a
 *    blanket re-trim would eat the `)` of an explicitly delimited
 *    `<https://example.com/a)>`, which mdast records identically to a literal;
 *  - here the scan IS the tokenizer, `<…>` autolinks are not a syntax this
 *    renderer supports, so [autolinkEnd] runs on every literal.
 *
 * The recognition rules are micromark 2.1.0's, not the GFM spec prose, which is
 * looser than the implementation in one place and stricter in two others (see
 * [isValidPreceding] and [hasValidDomain]). Email literals are the one form
 * deliberately not supported: micromark's email tokenizer already stops at CJK
 * punctuation, so the web fix never touched them.
 */
internal object Autolink {

    /** One literal found at a scan position: the source text it consumed, and its href. */
    data class Match(val text: String, val href: String)

    /**
     * The literal starting at [start], or null. [prev] is the character before
     * [start] in the same inline fragment, or null at a fragment boundary
     * (start of input, or a recursion into `**…**` / link text — all of which
     * micromark's rules accept as a preceding position anyway).
     */
    fun at(raw: String, start: Int, prev: Char?): Match? {
        val schemeLength = schemeLength(raw, start) ?: return null
        if (!isValidPreceding(prev, isWww = schemeLength == 0)) return null
        // `afterProtocol`, hoisted ahead of the scan because it is the reject
        // that fires on a long run of `https://,https://,…`: leaving it inside
        // [hasValidDomain] makes each doomed attempt walk the whole run first.
        if (schemeLength > 0 && !startsDomain(raw.getOrNull(start + schemeLength))) return null
        if (rejectedOnSight(raw, start + schemeLength)) return null

        // A literal runs to whitespace, `<`, or end of input — GFM's only
        // terminators, and the reason a CJK tail ends up inside one. `>` is
        // ours: micromark never meets one because its core tokenizer claims
        // `<https://x.com>` as a delimited autolink before the literal rules
        // run, and this renderer has no such rule, so without it the closing
        // bracket lands in the href and the link 404s.
        var end = start
        while (end < raw.length && !raw[end].isWhitespace() && raw[end] != '<' && raw[end] != '>') {
            end += 1
        }
        var literal = raw.substring(start, end)

        // Cut the CJK tail first, then let GFM's trail rules see the head they
        // never got to. Order matters: the ASCII punctuation this exposes is
        // exactly what nothing had trimmed.
        val cut = literal.indexOfFirst { it in CJK_PUNCTUATION }
        if (cut >= 0) literal = literal.substring(0, cut)
        literal = literal.substring(0, autolinkEnd(literal))

        if (!hasValidDomain(literal, schemeLength)) return null
        // `www.…` is not a url; mdast prepends the same scheme.
        return Match(literal, if (schemeLength == 0) "http://$literal" else literal)
    }

    /**
     * Length of the matched scheme: 0 for a `www.` literal, null when neither
     * matches. Case-insensitive, as micromark's `buffer.toLowerCase()` is.
     */
    private fun schemeLength(raw: String, start: Int): Int? = when {
        raw.startsWith("https://", start, ignoreCase = true) -> 8
        raw.startsWith("http://", start, ignoreCase = true) -> 7
        raw.startsWith("www.", start, ignoreCase = true) -> 0
        else -> null
    }

    /**
     * micromark's two `previous` guards, which are NOT the same rule:
     * `previousProtocol` is only "not an ASCII letter", while `previousWww` is a
     * delimiter allowlist (and includes `[` / `]`, which the spec prose omits).
     *
     * The difference is the whole point for CJK prose: `见https://example.com`
     * — a url glued straight onto Chinese text, with no space, the way it is
     * ordinarily written — is a link, because 见 is not an ASCII letter.
     */
    private fun isValidPreceding(prev: Char?, isWww: Boolean): Boolean {
        if (prev == null) return true
        if (!isWww) return !(prev in 'a'..'z' || prev in 'A'..'Z')
        // `markdownLineEndingOrSpace`, not general Unicode whitespace: an
        // ideographic or non-breaking space before a `www.` literal does NOT
        // open one, so `　www.example.com` is plain text here as it is on the
        // web. The scan's own terminator is the broader test, as micromark's is.
        return prev == ' ' || prev == '\t' || prev == '\r' || prev == '\n' ||
            prev == '(' || prev == '*' || prev == '_' ||
            prev == '[' || prev == ']' || prev == '~'
    }

    /**
     * A domain verdict that trimming provably cannot overturn, read straight
     * off [raw] before the literal is materialised. False means "cannot decide
     * yet" — the caller does the full job.
     *
     * Worth the extra walk because the full job is three passes over the whole
     * whitespace-free run (substring, CJK scan, trail table) for a candidate
     * that a handful of characters already doom. A run like
     * `https://a_b.com,https://a_b.com,…` starts one at every comma and rejects
     * every one of them: 64 kB of it measured at ~1.1 s on the main thread, and
     * a streaming message pays that per frame.
     *
     * Sound because the domain ends at [domainEnd]'s boundary character, and
     * trimming only removes a suffix of TRAIL characters. Neither an
     * alphanumeric nor a `-` is one, so if the character before the boundary is
     * one of those, nothing trimmed can reach into the domain and the verdict
     * is already final. When it IS a `.` or `_` the trim may shorten the domain
     * — `https://x.com._,` rejects here but accepts once trimmed to
     * `https://x.com` — so those are left to the slow path.
     */
    private fun rejectedOnSight(raw: String, domainStart: Int): Boolean {
        val end = domainEnd(raw, domainStart)
        if (end < 0) return false
        val last = raw[end - 1]
        if (last == '.' || last == '_') return false
        return !hasValidDomain(raw.substring(domainStart, end), 0)
    }

    /**
     * Index where micromark's domain walk stops, or -1 when this guard cannot
     * decide: the text ran out, or no boundary turned up within [MAX_DOMAIN].
     *
     * The cap matters because declining is the expensive answer. Without it
     * the guard walks the full run before giving up on exactly the runs it
     * cannot decide — the ones whose domain never meets a boundary — and adds
     * its own pass to a path that already had three.
     */
    private fun domainEnd(raw: String, from: Int): Int {
        val limit = minOf(raw.length, from + MAX_DOMAIN)
        var i = from
        while (i < limit) {
            val c = raw[i]
            if (c != '.' && c != '_' && c != '-' && isDomainBoundary(c)) return i
            i += 1
        }
        return -1
    }

    /** DNS's limit on a fully-qualified name; nothing longer is a host. */
    private const val MAX_DOMAIN = 253

    /**
     * micromark's `afterProtocol` guard followed by its `tokenizeDomain`,
     * walked over the already-trimmed literal.
     *
     * Deliberately NOT the spec's "there must be at least one period": the
     * implementation dropped that rule (`domainAfter` carries the comment "GH
     * says a dot is needed, but it's not true"), so `http://localhost/a` is a
     * link. What remains is a domain that saw at least one ordinary character
     * and carries no underscore in either of its last two segments.
     *
     * Walking it beats splitting the host at `/?#`, because the domain ends at
     * the FIRST unicode punctuation that is not `-`: the `:` of
     * `https://a.b_c:d/x` closes a last segment holding an underscore, and the
     * `,` of `https://,x.com/a` closes one holding nothing at all. A `/?#`
     * split reads each as one long host and admits both.
     */
    private fun hasValidDomain(literal: String, schemeLength: Int): Boolean {
        if (literal.length <= schemeLength) return false

        var underscoreInLastSegment = false
        var underscoreInLastLastSegment = false
        var seen = false
        for (i in schemeLength until literal.length) {
            when (val c = literal[i]) {
                // A `.`/`_` whose remainder is all trailing punctuation would
                // end the domain instead, but [at] already trimmed those, so
                // every one still standing is segment punctuation.
                '.' -> {
                    underscoreInLastLastSegment = underscoreInLastSegment
                    underscoreInLastSegment = false
                }
                '_' -> underscoreInLastSegment = true
                // `seen` spans the whole domain, not the current segment: it is
                // set once and never cleared, so `https://x./a` is a link.
                else -> {
                    if (c != '-' && isDomainBoundary(c)) break
                    seen = true
                }
            }
        }
        return seen && !underscoreInLastSegment && !underscoreInLastLastSegment
    }

    /**
     * micromark's `afterProtocol`: the character right after `://` may not be
     * punctuation at all, and here — unlike inside the domain — `-` gets no
     * exception, so `https://-x.com` is not a link. The `www.` form has no such
     * guard; its domain starts at the `w`, which is why [schemeLength] is 0.
     */
    private fun startsDomain(first: Char?): Boolean =
        first != null && !first.isISOControl() && !isDomainBoundary(first)

    /**
     * Where micromark's domain stops: `unicodeWhitespace` or
     * `unicodePunctuation`, the latter being `\p{P}|\p{S}` as of
     * micromark-util-character 2.1 — which is why `$`, `+`, `=`, `|` and `~`
     * end a domain despite not being punctuation in the ASCII sense.
     */
    private fun isDomainBoundary(c: Char): Boolean = c.isWhitespace() ||
        c.category in PUNCTUATION_CATEGORIES

    private val PUNCTUATION_CATEGORIES = setOf(
        CharCategory.CONNECTOR_PUNCTUATION,
        CharCategory.DASH_PUNCTUATION,
        CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION,
        CharCategory.INITIAL_QUOTE_PUNCTUATION,
        CharCategory.FINAL_QUOTE_PUNCTUATION,
        CharCategory.OTHER_PUNCTUATION,
        CharCategory.MATH_SYMBOL,
        CharCategory.CURRENCY_SYMBOL,
        CharCategory.MODIFIER_SYMBOL,
        CharCategory.OTHER_SYMBOL,
    )

    /** Mirrors the set in `@streamdown/cjk` so both clients cut at the same place. */
    private val CJK_PUNCTUATION = "。．，、？！：；（）【】「」『』〈〉《》".toSet()

    /** Punctuation that may end the url, per micromark's `trail` states. */
    private val TRAIL = "!\"')*,.:;?_~".toSet()

    /**
     * Punctuation that makes micromark's `pathInside` attempt a trail check.
     * micromark also lists `<`, which cannot occur here — [at] stops the scan
     * before one — so it is omitted rather than left as an unreachable branch.
     */
    private val PATH_PUNCTUATION = TRAIL + '&' + ']'

    private val ENTITY = Regex("""&[A-Za-z]+;""")

    /**
     * `trail[i]` = everything from `i` on is trailing punctuation: a run of
     * trail characters, `&entity;` groups, and `]` reaching the end of [url].
     *
     * micromark answers this with a forward re-scan at every punctuation
     * character, which is quadratic on a path like `…/.....a` — 16 000 dots
     * measured at ~280 ms, on the main thread during composition. The predicate
     * is suffix-closed, so one backward pass computes the same answers.
     *
     * [url] carries [at]'s contract: no whitespace and no `<`, since the scan
     * ends at either. micromark's checks for those are elided accordingly; the
     * one that survives is `]` at the very end, where its lookahead is absent.
     */
    private fun trailSuffixes(url: String): BooleanArray {
        val trail = BooleanArray(url.length + 1)
        trail[url.length] = true
        for (i in url.length - 1 downTo 0) {
            val c = url[i]
            trail[i] = when {
                c in TRAIL -> trail[i + 1]
                // `]` ends the url when it could close a `[…](…)` / `[…][…]`
                // wrapper, and is otherwise just another character to skip.
                c == ']' -> when (url.getOrNull(i + 1)) {
                    null, '(', '[' -> true
                    else -> trail[i + 1]
                }
                // `&` + alphabeticals + `;` counts as trailing punctuation as a
                // whole; anything else after `&` is url content.
                c == '&' -> ENTITY.matchAt(url, i)?.let { trail[i + it.value.length] } == true
                else -> false
            }
        }
        return trail
    }

    /**
     * Index at which [url] stops being a link, per GFM's trailing-punctuation
     * and unbalanced-parenthesis rules. `https://x.com/a)` → 15, but the `)` of
     * `https://x.com/a(b)` is kept because its parentheses balance.
     */
    fun autolinkEnd(url: String): Int {
        val trail = trailSuffixes(url)
        var open = 0
        var close = 0
        var i = 0
        while (i < url.length) {
            val c = url[i]
            if (c == '(') {
                open += 1
                i += 1
                continue
            }
            // A `)` that still has an unclosed `(` before it is url content,
            // never a trail — that is what keeps `…/Foo_(bar)` intact.
            if (c == ')' && close < open) {
                close += 1
                i += 1
                continue
            }
            if (c in PATH_PUNCTUATION) {
                if (trail[i]) return i
                if (c == ')') close += 1
                i += 1
                continue
            }
            i += 1
        }
        return url.length
    }
}
