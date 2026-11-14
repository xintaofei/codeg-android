package app.codeg.android.core.designsystem.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import app.codeg.android.core.designsystem.theme.CodegTheme

/** Cached inline-markdown for the current theme colors (recomputed if colors change).
 *  Slot-scoped `remember` only — deliberately NOT a shared cache, so the growing tail of
 *  a streaming block can't churn/evict persisted spans. */
@Composable
fun rememberInlineMarkdown(raw: String): AnnotatedString {
    val colors = CodegTheme.colors
    return remember(raw, colors.accent, colors.codeSurface, colors.textPrimary) {
        inlineMarkdown(
            raw = raw,
            linkColor = colors.accent,
            codeBg = colors.codeSurface,
            codeColor = colors.textPrimary,
        )
    }
}

/**
 * Inline-Markdown → [AnnotatedString] (the Compose analogue of iOS
 * `MarkdownText.attributed(from:)`, which used Apple's inline parser). Supports
 * `**bold**` / `__bold__`, `*italic*` / `_italic_`, `~~strike~~`, `` `code` ``,
 * `[text](url)` links, and bare GFM autolink literals (see [Autolink]). Nesting
 * is handled by recursion; code spans are literal.
 *
 * Links are emitted as [LinkAnnotation.Url], which Compose's own `Text` opens
 * through `LocalUriHandler` — no click plumbing at the call sites — for the
 * schemes that handler can survive; see [openableLink].
 */
fun inlineMarkdown(
    raw: String,
    linkColor: Color,
    codeBg: Color,
    codeColor: Color,
): AnnotatedString = buildAnnotatedString {
    parseInline(this, raw, linkColor, codeBg, codeColor)
}

/**
 * [inLink] suppresses autolinking inside a `[text](url)` label, where a nested
 * link has no meaning and no way to be tapped.
 *
 * [openBrackets] is micromark's `previousUnbalanced`: a literal sitting after a
 * `[` that has not been closed yet is not a link, because it may still turn out
 * to be a label. It is why `[www.example.com` and `[www.example.com]` are both
 * plain text while `[b] www.example.com` is not. The count is carried into the
 * emphasis recursions so `[a **www.example.com**` stays suppressed too, and
 * handed BACK by them: micromark counts brackets across the whole paragraph, so
 * the `[` in `*[a* www.example.com` keeps suppressing after the emphasis closes.
 * Returning it is also what keeps [literalMask], which walks straight through
 * emphasis, counting the same brackets this does.
 */
private fun parseInline(
    builder: AnnotatedString.Builder,
    raw: String,
    linkColor: Color,
    codeBg: Color,
    codeColor: Color,
    inLink: Boolean = false,
    openBrackets: Int = 0,
): Int {
    var i = 0
    val n = raw.length
    val plain = StringBuilder()
    var open = openBrackets
    var hasClose = true
    var knownClose = -1

    // Computed on the first emphasis delimiter, so prose with no `*`/`_`/`~~`
    // never pays for the extra scan.
    var mask: BooleanArray? = null
    var masked = false
    /**
     * The mask, computed from this fragment's own starting bracket depth and
     * its own link context, so it marks exactly what this walk will jump — see
     * [literalMask].
     */
    fun activeMask(): BooleanArray? {
        if (!masked) {
            mask = literalMask(raw, openBrackets, inLink)
            masked = true
        }
        return mask
    }

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            builder.append(plain.toString())
            plain.clear()
        }
    }

    while (i < n) {
        val c = raw[i]
        when {
            // Inline code — literal content, no recursion.
            c == '`' -> {
                val end = raw.indexOf('`', i + 1)
                if (end > i) {
                    flushPlain()
                    builder.withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = codeColor),
                    ) { append(raw.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    plain.append(c); i++
                }
            }

            // Bold (** or __).
            (c == '*' || c == '_') && i + 1 < n && raw[i + 1] == c -> {
                val marker = "$c$c"
                val end = if (c == '_' && intraword(raw, i, 2)) -1 else closingRun(raw, marker, i + 2, activeMask())
                if (end > i + 1) {
                    flushPlain()
                    builder.withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        open = parseInline(builder, raw.substring(i + 2, end), linkColor, codeBg, codeColor, inLink, open)
                    }
                    i = end + 2
                } else {
                    plain.append(c); i++
                }
            }

            // Strikethrough (~~).
            c == '~' && i + 1 < n && raw[i + 1] == '~' -> {
                val end = closingRun(raw, "~~", i + 2, activeMask())
                if (end > i + 1) {
                    flushPlain()
                    builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        open = parseInline(builder, raw.substring(i + 2, end), linkColor, codeBg, codeColor, inLink, open)
                    }
                    i = end + 2
                } else {
                    plain.append(c); i++
                }
            }

            // Italic (single * or _).
            (c == '*' || c == '_') -> {
                val end = if (c == '_' && intraword(raw, i, 1)) -1 else closingRun(raw, c.toString(), i + 1, activeMask())
                if (end > i) {
                    flushPlain()
                    builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        open = parseInline(builder, raw.substring(i + 1, end), linkColor, codeBg, codeColor, inLink, open)
                    }
                    i = end + 1
                } else {
                    plain.append(c); i++
                }
            }

            // Link [text](url).
            c == '[' -> {
                // Once no `]` remains, none remains for any later `[` either.
                // Without the memo every bracket in a run rescans to the end of
                // the fragment, which is quadratic — 32 000 of them measured at
                // ~650 ms, on the main thread, per recomposition.
                //
                // [knownClose] is what keeps the probe itself from being the new
                // quadratic term: re-probing is pointless until the scan has
                // passed the `]` the last probe found, and probing only once
                // would strand every bracket after an early `]` on the slow path.
                val close = if (hasClose) labelEnd(raw, i + 1) else -1
                if (close < 0 && hasClose && i + 1 > knownClose) {
                    val next = raw.indexOf(']', i + 1)
                    if (next < 0) hasClose = false else knownClose = next
                }
                val urlEnd = if (close > i && close + 1 < n && raw[close + 1] == '(') {
                    destinationEnd(raw, close + 2)
                } else {
                    -1
                }
                val text = if (urlEnd > close) raw.substring(i + 1, close) else ""
                if (urlEnd > close && !containsLink(text)) {
                    val url = raw.substring(close + 2, urlEnd)
                    flushPlain()
                    // A destination we cannot hand to an Intent is styled but
                    // left inert — see [openableLink]. So is one already inside
                    // a link: only an image can be there, and a second
                    // annotation over the same characters leaves which
                    // destination a tap opens undefined.
                    val link = if (inLink) null else openableLink(url, linkColor)
                    if (link != null) {
                        builder.withLink(link) {
                            parseInline(builder, text, linkColor, codeBg, codeColor, inLink = true)
                        }
                    } else {
                        builder.withStyle(SpanStyle(color = linkColor)) {
                            parseInline(builder, text, linkColor, codeBg, codeColor, inLink = true)
                        }
                    }
                    i = urlEnd + 1
                } else {
                    // Not a link, but still an open bracket until its `]`.
                    open += 1
                    plain.append(c); i++
                }
            }

            // Bare url. Only `h`/`w` can start one, so the scan costs a char
            // compare on everything else.
            (c == 'h' || c == 'H' || c == 'w' || c == 'W') && !inLink && open == 0 -> {
                val link = Autolink.at(raw, i, if (i == 0) null else raw[i - 1])
                if (link != null) {
                    flushPlain()
                    // The scheme is http/https by construction, so this is never inert.
                    val annotation = openableLink(link.href, linkColor)
                    if (annotation != null) builder.withLink(annotation) { append(link.text) }
                    else builder.withStyle(SpanStyle(color = linkColor)) { append(link.text) }
                    i += link.text.length
                } else {
                    plain.append(c); i++
                }
            }

            else -> {
                if (c == ']' && open > 0) open -= 1
                plain.append(c); i++
            }
        }
    }
    flushPlain()
    return open
}

/**
 * CommonMark's intraword rule, which applies to `_` and not to `*`: a `_`
 * delimiter run with an alphanumeric on BOTH sides can neither open nor close
 * emphasis. Without it `snake_case_name` renders its middle word in italics,
 * and — since the split fragments are then autolinked separately —
 * `_https://x_y.com` yields a tappable link to `https://x`, a destination
 * nobody wrote.
 */
private fun intraword(raw: String, start: Int, runLength: Int): Boolean {
    val before = raw.getOrNull(start - 1) ?: return false
    val after = raw.getOrNull(start + runLength) ?: return false
    return before.isLetterOrDigit() && after.isLetterOrDigit()
}

/**
 * First [marker] at or after [from] that is allowed to close, or -1. A run is
 * skipped when it is intraword (`_` only) or when it sits inside an autolink
 * literal — see [literalMask].
 */
private fun closingRun(raw: String, marker: String, from: Int, mask: BooleanArray?): Int {
    var at = raw.indexOf(marker, from)
    while (at >= 0 && (mask?.get(at) == true || (marker[0] == '_' && intraword(raw, at, marker.length)))) {
        at = raw.indexOf(marker, at + 1)
    }
    return at
}

/**
 * Which characters [parseInline] will never offer as an emphasis delimiter, or
 * null when there are none: everything inside an autolink literal, a code span,
 * or an explicit `[…](…)`. Emphasis consults it before accepting a closer.
 *
 * micromark gets this ordering for free — those three are tokenized in the text
 * phase and emphasis only ever sees what is left. This parser walks once, and
 * jumps each of them whole, so a delimiter inside one is never reached as text
 * yet `indexOf` still finds it. Each miss truncates something a reader can see:
 *
 *  - `_https://x.com/Foo_(bar)` ends its italics on the `_` of `Foo_` and links
 *    `https://x.com/Foo`, a destination nobody wrote;
 *  - `_[foo](https://x.com/p_)_` closes on the `_` in the DESTINATION, breaking
 *    the link syntax open and linking the truncated `https://x.com/p`;
 *  - `` *a `x*y` b* `` closes on the `*` inside the code span, losing the span
 *    and printing its backticks.
 *
 * It has to repeat [parseInline]'s own walk — code spans, links, the unclosed-
 * bracket count — because the answer is per-url, not per-fragment, and the two
 * shapes that prove it pull in opposite directions:
 *
 *  - `[_foo] https://a.com/x_/y_` — the url is AFTER the `]`, so it links, and
 *    the mask must cover it or its `_` closes the run and the link points at
 *    `https://a.com/x` under text reading `…/x_/y`;
 *  - `[note _https://a.com/x_/y] [results](…?q=foo_)` — the url is INSIDE the
 *    bracket, so it does not link, and the mask must NOT cover it or the run
 *    stays open until the `_` in the next link's destination, swallowing the
 *    markdown in between.
 *
 * Both open their emphasis inside the bracket, so no test taken where the run
 * OPENS can separate them.
 */
private fun literalMask(raw: String, openBrackets: Int, inLink: Boolean): BooleanArray? {
    var mask: BooleanArray? = null
    var open = openBrackets
    var hasClose = true
    var knownClose = -1
    var i = 0

    fun mark(from: Int, until: Int) {
        val marked = mask ?: BooleanArray(raw.length).also { mask = it }
        for (k in from until until) marked[k] = true
    }

    while (i < raw.length) {
        when (raw[i]) {
            '`' -> {
                val end = raw.indexOf('`', i + 1)
                if (end > i) {
                    mark(i, end + 1)
                    i = end + 1
                } else {
                    // Unterminated: the walk treats it as plain text, so the
                    // delimiters behind it stay available.
                    i += 1
                }
            }

            '[' -> {
                val close = if (hasClose) labelEnd(raw, i + 1) else -1
                if (close < 0 && hasClose && i + 1 > knownClose) {
                    val next = raw.indexOf(']', i + 1)
                    if (next < 0) hasClose = false else knownClose = next
                }
                val urlEnd = if (close > i && close + 1 < raw.length && raw[close + 1] == '(') {
                    destinationEnd(raw, close + 2)
                } else {
                    -1
                }
                if (urlEnd > close && !containsLink(raw.substring(i + 1, close))) {
                    // The walk jumps the whole link: its label is re-parsed with
                    // its own mask, and its destination is never text at all.
                    mark(i, urlEnd + 1)
                    i = urlEnd + 1
                } else {
                    open += 1
                    i += 1
                }
            }

            ']' -> {
                if (open > 0) open -= 1
                i += 1
            }

            'h', 'H', 'w', 'W' -> {
                // Inside a label no literal is emitted, but the code spans and
                // links around it are still jumped whole, so the rest of the
                // mask is needed there just the same.
                val match = if (!inLink && open == 0) {
                    Autolink.at(raw, i, if (i == 0) null else raw[i - 1])
                } else {
                    null
                }
                if (match != null) {
                    mark(i, i + match.text.length)
                    i += match.text.length
                } else {
                    i += 1
                }
            }

            else -> i += 1
        }
    }
    return mask
}

/**
 * Whether [label] holds a `[…](…)` of its own. CommonMark forbids links inside
 * links at any depth: the INNER one wins and the outer brackets stay literal
 * text, which is why `[a [b](y) c](z)` renders as `[a `, a link on `b`, then
 * ` c](` and a bare autolink on `z`.
 *
 * Emitting the outer link anyway gave Compose two `LinkAnnotation` ranges where
 * one contained the other, so which destination a tap opened was undefined.
 *
 * A `[` behind a `!` is an image, which a link MAY contain, so it does not
 * count — the label still renders without the picture (see the image task), but
 * the surrounding link survives. Code spans are skipped for the same reason:
 * the brackets in ``[a `[b](y)` c](z)`` are code, not a link, and reading them
 * as one costs the label the only real link it had.
 */
private fun containsLink(label: String): Boolean {
    var i = 0
    while (i < label.length) {
        if (label[i] == '`') {
            val close = label.indexOf('`', i + 1)
            if (close > i) {
                i = close + 1
                continue
            }
        }
        if (label[i] == '[' && (i == 0 || label[i - 1] != '!')) {
            val close = labelEnd(label, i + 1)
            if (close > i && close + 1 < label.length && label[close + 1] == '(' &&
                destinationEnd(label, close + 2) > close
            ) {
                return true
            }
        }
        i += 1
    }
    return false
}

/**
 * End of a `[…]` label, balancing nested brackets, or -1 when the `[` is never
 * closed. Taking the first `]` loses any label holding a bracket of its own:
 * `[https://x.com/p/[id]](https://real/)` stopped at the `]` of `[id]`, failed
 * to see the `(` after it, and rendered the whole thing as text with the
 * destination showing as a separate autolink.
 */
private fun labelEnd(raw: String, start: Int): Int = matchingClose(raw, start, '[', ']')

/**
 * End of a `[…](…)` destination, balancing nested parentheses, or -1 when the
 * `(` is never closed. Taking the first `)` truncated every url that contains a
 * balanced pair — `…/Function_(mathematics))` opened one character short — which
 * was invisible while links were inert text and is not any more.
 */
private fun destinationEnd(raw: String, start: Int): Int = matchingClose(raw, start, '(', ')')

/** Index of the [close] that balances an already-consumed [open], or -1. */
private fun matchingClose(raw: String, start: Int, open: Char, close: Char): Int {
    var depth = 0
    var i = start
    while (i < raw.length) {
        when (raw[i]) {
            open -> depth += 1
            close -> {
                if (depth == 0) return i
                depth -= 1
            }
        }
        i += 1
    }
    return -1
}

/**
 * Schemes worth handing to an `ACTION_VIEW` intent at all. Agent replies do
 * carry others — the web rewrites `file://` links to an in-app file open, which
 * Android has no viewer for — and those render in the link colour and do
 * nothing, as they did before links were tappable.
 *
 * This is about intent, not safety: whether a destination resolves depends on
 * the device, not on the text, so no allowlist can decide it. `SafeUriHandler`
 * in `CodegTheme` is what keeps a tap from crashing the app.
 */
private val OPENABLE_SCHEMES = setOf("http", "https", "mailto", "tel")

private fun openableLink(url: String, linkColor: Color): LinkAnnotation.Url? {
    val written = url.substringBefore(':', missingDelimiterValue = "")
    val scheme = written.lowercase()
    if (scheme !in OPENABLE_SCHEMES) return null
    // `IntentFilter` matches its data scheme case-sensitively, against the
    // lowercase one browsers register — so `HTTPS://x.com`, which GFM accepts
    // and the autolink path does reach, resolves no activity and throws. Only
    // the destination is folded; the text keeps the casing it was written in.
    val href = if (written == scheme) url else scheme + url.substring(written.length)
    return LinkAnnotation.Url(href, TextLinkStyles(style = SpanStyle(color = linkColor)))
}
