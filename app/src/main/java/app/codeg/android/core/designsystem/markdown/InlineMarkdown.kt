package app.codeg.android.core.designsystem.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * Inline-Markdown → [AnnotatedString] (the Compose analogue of iOS
 * `MarkdownText.attributed(from:)`, which used Apple's inline parser). Supports
 * `**bold**` / `__bold__`, `*italic*` / `_italic_`, `~~strike~~`, `` `code` ``,
 * and `[text](url)` links. Nesting is handled by recursion; code spans are
 * literal. Link URLs are attached as a string annotation under tag `URL`.
 */
const val URL_TAG = "URL"
val LocalMarkdownLinkHandler = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Cached inline-markdown for the current theme colors (recomputed if colors change).
 *  Slot-scoped `remember` only — deliberately NOT a shared cache, so the growing tail of
 *  a streaming block can't churn/evict persisted spans. */
@Composable
fun rememberInlineMarkdown(raw: String): AnnotatedString {
    val colors = CodegTheme.colors
    val handler by rememberUpdatedState(LocalMarkdownLinkHandler.current)
    val uriHandler by rememberUpdatedState(LocalUriHandler.current)
    return remember(raw, colors.accent, colors.codeSurface, colors.textPrimary) {
        val parsed = inlineMarkdown(
            raw = raw,
            linkColor = colors.accent,
            codeBg = colors.codeSurface,
            codeColor = colors.textPrimary,
        )
        buildAnnotatedString {
            append(parsed)
            parsed.getStringAnnotations(URL_TAG, 0, parsed.length).forEach { annotation ->
                addLink(LinkAnnotation.Clickable(annotation.item) {
                    val onLink = handler
                    if (onLink != null) onLink(annotation.item)
                    else if (annotation.item.startsWith("https://") || annotation.item.startsWith("http://")) {
                        runCatching { uriHandler.openUri(annotation.item) }
                    }
                }, annotation.start, annotation.end)
            }
        }
    }
}

fun inlineMarkdown(
    raw: String,
    linkColor: Color,
    codeBg: Color,
    codeColor: Color,
): AnnotatedString = buildAnnotatedString {
    parseInline(this, raw, linkColor, codeBg, codeColor)
}

private fun parseInline(
    builder: AnnotatedString.Builder,
    raw: String,
    linkColor: Color,
    codeBg: Color,
    codeColor: Color,
) {
    var i = 0
    val n = raw.length
    val plain = StringBuilder()

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
                val end = raw.indexOf(marker, i + 2)
                if (end > i + 1) {
                    flushPlain()
                    builder.withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        parseInline(builder, raw.substring(i + 2, end), linkColor, codeBg, codeColor)
                    }
                    i = end + 2
                } else {
                    plain.append(c); i++
                }
            }

            // Strikethrough (~~).
            c == '~' && i + 1 < n && raw[i + 1] == '~' -> {
                val end = raw.indexOf("~~", i + 2)
                if (end > i + 1) {
                    flushPlain()
                    builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        parseInline(builder, raw.substring(i + 2, end), linkColor, codeBg, codeColor)
                    }
                    i = end + 2
                } else {
                    plain.append(c); i++
                }
            }

            // Italic (single * or _).
            (c == '*' || c == '_') -> {
                val end = raw.indexOf(c, i + 1)
                if (end > i) {
                    flushPlain()
                    builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        parseInline(builder, raw.substring(i + 1, end), linkColor, codeBg, codeColor)
                    }
                    i = end + 1
                } else {
                    plain.append(c); i++
                }
            }

            // Link [text](url).
            c == '[' -> {
                val close = raw.indexOf(']', i + 1)
                if (close > i && close + 1 < n && raw[close + 1] == '(') {
                    val urlEnd = linkDestinationEnd(raw, close + 2)
                    if (urlEnd > close) {
                        val text = raw.substring(i + 1, close)
                        val url = raw.substring(close + 2, urlEnd).trim().removeSurrounding("<", ">")
                        flushPlain()
                        builder.pushStringAnnotation(URL_TAG, url)
                        builder.withStyle(SpanStyle(color = linkColor)) {
                            parseInline(builder, text, linkColor, codeBg, codeColor)
                        }
                        builder.pop()
                        i = urlEnd + 1
                    } else {
                        plain.append(c); i++
                    }
                } else {
                    plain.append(c); i++
                }
            }

            else -> {
                plain.append(c); i++
            }
        }
    }
    flushPlain()
}

/** Parentheses in paths are common in build outputs; angle brackets allow spaces. */
private fun linkDestinationEnd(raw: String, start: Int): Int {
    if (raw.getOrNull(start) == '<') {
        val end = raw.indexOf('>', start + 1)
        return if (end >= 0 && raw.getOrNull(end + 1) == ')') end + 1 else -1
    }
    var depth = 1
    var escaped = false
    for (i in start until raw.length) {
        if (escaped) { escaped = false; continue }
        when (raw[i]) {
            '\\' -> escaped = true
            '(' -> depth++
            ')' -> if (--depth == 0) return i
        }
    }
    return -1
}
