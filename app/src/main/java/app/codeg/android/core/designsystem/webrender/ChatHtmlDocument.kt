package app.codeg.android.core.designsystem.webrender

import java.io.ByteArrayOutputStream

/**
 * The chat HTML preview's document, built from a ```` ```html ```` fence the way the web's
 * `buildChatHtmlDocument` builds it (`chat-html-document.ts`, `html-preview-inline.ts`).
 * Port of iOS `ChatHtmlDocument`:
 *
 * 1. **Strip active content** (`inlineHtmlResources` with `stripActiveContent`): `script`,
 *    `iframe`, `object`, `embed`, `link` and `template` elements go, with their content; so
 *    do SVG `set`/`animate` that write a link target, author CSP and refresh `<meta>`, and
 *    `<base>`. On every element left, `on*` handlers, `href`/`xlink:href` other than a
 *    `#fragment` into the document, `action`, `formaction`, `nonce` and `srcdoc` go. No
 *    resource is inlined (the chat reads no local files).
 * 2. **Normalize** to `<!DOCTYPE html><html><head>…</head><body>…</body></html>`, split at
 *    the source's real `<body>` (else after the leading metadata), keeping the wrappers'
 *    attributes — minus active ones.
 * 3. **Theme** — color scheme, base font, the theme's CSS variables — first in `<head>`,
 *    ahead of the author's CSS.
 * 4. **Sandbox**: the strict CSP and a pinned `<base>` before everything.
 *
 * Unlike the web, the document carries no script at all: this client runs none in it (no
 * nonce, no size reporter — the height is measured by the app), and adds a viewport so the
 * WebView lays it out at the column's width.
 *
 * The web parses with a `<template>`; this works on the HTML tokenizer's rules
 * ([HtmlTokenizer]): comments, quoted attributes and raw-text elements are honoured, so
 * markup-looking text inside them is never taken for tags. What it keeps, it keeps byte for
 * byte. The preview also renders with JavaScript off and every network load blocked, so this
 * pass is defence in depth, not the only line.
 */
object ChatHtmlDocument {
    /** The web's `CSP_STRICT`, verbatim. */
    val STRICT_CSP: String = listOf(
        "default-src 'none'",
        "img-src data: blob:",
        "media-src data: blob:",
        "style-src 'unsafe-inline' data:",
        "font-src data:",
        "script-src 'none'",
        "form-action 'none'",
        "base-uri about:",
        "frame-src 'none'",
    ).joinToString("; ")

    /** The web's `SANDBOX_BASE`: in-page `#fragment` links resolve against the document itself. */
    const val SANDBOX_BASE = """<base href="about:srcdoc">"""

    /** Lays the document out at the view's width; the last viewport wins, so it goes last in head. */
    const val VIEWPORT_META = """<meta name="viewport" content="width=device-width,initial-scale=1,shrink-to-fit=no">"""

    /** The full preview document for a fence's source. */
    fun build(source: String, theme: ChatHtmlTheme): String {
        val html = inlineStripped(source, headEnd = VIEWPORT_META)
        val themed = withHtmlPreviewHead(html, themeStyle(theme))
        return withSandboxCsp(themed)
    }

    /** The web's injected `<style>`, plus the theme's surface under the page and no text inflation. */
    fun themeStyle(theme: ChatHtmlTheme): String = buildString {
        append("<style>\n")
        append(":root{color-scheme:").append(if (theme.dark) "dark" else "light").append("}\n")
        append("html,body{margin:0;background:transparent;color:var(--foreground,")
        append(if (theme.dark) "#fafafa" else "#1a1c1f")
        append(");font:14px/1.5 system-ui,-apple-system,\"Segoe UI\",sans-serif}\n")
        append("*,*::before,*::after{box-sizing:border-box}\n")
        append(theme.variablesCss).append('\n')
        append("html{background:").append(theme.surface.css).append(";-webkit-text-size-adjust:100%}\n")
        append("</style>")
    }

    // region Sandbox

    /**
     * The strict CSP, then the pinned base, as the first things in the real `<head>`
     * (`withSandboxCsp`): the policy is in force before the base is read, and the base is
     * the document's only one.
     */
    fun withSandboxCsp(html: String): String =
        withHtmlPreviewHead(html, """<meta http-equiv="Content-Security-Policy" content="$STRICT_CSP">$SANDBOX_BASE""")

    /**
     * Insert [head] right after the document's real `<head>` start tag — not one inside a
     * comment, an attribute or raw text (`withHtmlPreviewHead`). With no `<head>`, a new one
     * goes after `<html>`, else in front.
     */
    fun withHtmlPreviewHead(html: String, head: String): String {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val tokens = HtmlTokenizer.tokenize(bytes)
        firstRealStartTag("head", tokens)?.let { return splice(bytes, it.range.last + 1, head) }
        firstRealStartTag("html", tokens)?.let { return splice(bytes, it.range.last + 1, "<head>$head</head>") }
        return "<head>$head</head>$html"
    }

    private fun splice(bytes: ByteArray, at: Int, insert: String): String {
        val out = ByteArrayOutputStream(bytes.size + insert.length * 3)
        out.write(bytes, 0, at)
        out.write(insert.toByteArray(Charsets.UTF_8))
        out.write(bytes, at, bytes.size - at)
        return out.toString(Charsets.UTF_8.name())
    }

    /** The first `<name>` start tag outside any `<template>` content. */
    private fun firstRealStartTag(name: String, tokens: List<HtmlTokenizer.Token>): HtmlTokenizer.Tag? =
        firstRealStartTagIndex(name, tokens)?.let { (tokens[it] as HtmlTokenizer.Token.StartTag).tag }

    private fun firstRealStartTagIndex(name: String, tokens: List<HtmlTokenizer.Token>): Int? {
        var templateDepth = 0
        tokens.forEachIndexed { index, token ->
            when (token) {
                is HtmlTokenizer.Token.StartTag -> {
                    if (templateDepth == 0 && token.tag.name == name) return index
                    if (token.tag.name == "template") templateDepth += 1
                }
                is HtmlTokenizer.Token.EndTag -> if (token.name == "template" && templateDepth > 0) templateDepth -= 1
                else -> Unit
            }
        }
        return null
    }

    // endregion

    // region Strip + normalize

    /**
     * The normalized, stripped document (`inlineHtmlResources(html, {stripActiveContent:
     * true, maxInlineBytes: 0})`). [headEnd] is appended inside `<head>`, after the author's
     * head content.
     */
    fun inlineStripped(html: String, headEnd: String = ""): String {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val tokens = HtmlTokenizer.tokenize(bytes)
        val wrappers = wrapperAttributes(tokens, bytes)

        val head: List<HtmlTokenizer.Token>
        val body: List<HtmlTokenizer.Token>
        val bodyIndex = firstRealStartTagIndex("body", tokens)
        if (bodyIndex != null) {
            // Split where the source's own <body> starts, so body-leading
            // <script>/<style>/<meta> stay in the body.
            head = sanitize(tokens.subList(0, bodyIndex), bytes)
            body = sanitize(tokens.subList(bodyIndex + 1, tokens.size), bytes)
        } else {
            // No <body>: the leading metadata, comments and blank text are the head;
            // everything from the first other node on is the body.
            val all = sanitize(tokens, bytes)
            val split = headSplit(all, bytes)
            head = all.subList(0, split)
            body = all.subList(split, all.size)
        }
        return "<!DOCTYPE html>\n<html${wrappers.html}>\n<head${wrappers.head}>" +
            serialize(head, bytes) + headEnd +
            "</head>\n<body${wrappers.body}>" + serialize(body, bytes) +
            "</body>\n</html>"
    }

    private data class Wrappers(val html: String, val head: String, val body: String)

    /**
     * Attribute text of the first real `<html>`, `<head>` and `<body>` start tags
     * (`extractWrapperAttrs`), active attributes removed, each as ` name=value` as written.
     */
    private fun wrapperAttributes(tokens: List<HtmlTokenizer.Token>, bytes: ByteArray): Wrappers {
        val found = HashMap<String, HtmlTokenizer.Tag>()
        var templateDepth = 0
        for (token in tokens) {
            when (token) {
                is HtmlTokenizer.Token.StartTag -> {
                    val tag = token.tag
                    if (templateDepth == 0 && tag.name in WRAPPERS && tag.name !in found) found[tag.name] = tag
                    if (tag.name == "template") templateDepth += 1
                }
                is HtmlTokenizer.Token.EndTag -> if (token.name == "template" && templateDepth > 0) templateDepth -= 1
                else -> Unit
            }
            if ("body" in found) break
        }
        fun text(name: String): String {
            val tag = found[name] ?: return ""
            return tag.attributes.filter { keeps(it, bytes) }.joinToString("") { " " + string(bytes, it.range) }
        }
        return Wrappers(text("html"), text("head"), text("body"))
    }

    // endregion

    // region Sanitizing

    private val WRAPPERS = setOf("html", "head", "body")

    /** Removed with their content. */
    val REMOVED_ELEMENTS = setOf("script", "iframe", "object", "embed", "link", "template")

    /** The HTML parser keeps these in `<head>` (`METADATA_TAGS`). */
    private val METADATA_ELEMENTS = setOf(
        "base", "basefont", "bgsound", "link", "meta", "noscript", "script", "style", "template", "title",
    )

    private val VOID_ELEMENTS = setOf(
        "area", "base", "basefont", "bgsound", "br", "col", "embed", "frame", "hr", "img", "input",
        "keygen", "link", "meta", "param", "source", "track", "wbr",
    )

    private fun sanitize(tokens: List<HtmlTokenizer.Token>, bytes: ByteArray): List<HtmlTokenizer.Token> {
        val out = ArrayList<HtmlTokenizer.Token>(tokens.size)
        var index = 0
        while (index < tokens.size) {
            when (val token = tokens[index]) {
                is HtmlTokenizer.Token.StartTag -> {
                    val tag = token.tag
                    // `html`/`head`/`body` tags inside the source: the web's template parse drops
                    // them, and the normalized document has its own.
                    if (tag.name in WRAPPERS) {
                        index += 1
                        continue
                    }
                    if (removes(tag, bytes)) {
                        index = elementEnd(tokens, index, tag, removing = true)
                        continue
                    }
                    out.add(HtmlTokenizer.Token.StartTag(tag.copy(attributes = tag.attributes.filter { keeps(it, bytes) })))
                }
                is HtmlTokenizer.Token.EndTag -> if (token.name !in WRAPPERS) out.add(token)
                is HtmlTokenizer.Token.Doctype -> Unit
                is HtmlTokenizer.Token.Text, is HtmlTokenizer.Token.Comment, is HtmlTokenizer.Token.RawText -> out.add(token)
            }
            index += 1
        }
        return out
    }

    /** Whether the element this tag starts is removed. */
    private fun removes(tag: HtmlTokenizer.Tag, bytes: ByteArray): Boolean {
        if (tag.name in REMOVED_ELEMENTS || tag.name == "base") return true
        return when (tag.name) {
            // SVG animation can set a link target the attribute pass never sees.
            "set", "animate" -> isLinkTarget(value("attributename", tag, bytes).orEmpty())
            "meta" -> {
                // An author CSP could tighten or confuse ours; a refresh redirects.
                val kind = value("http-equiv", tag, bytes)?.trim()?.lowercase() ?: return false
                kind == "content-security-policy" || kind == "refresh"
            }
            else -> false
        }
    }

    /** Whether an attribute survives (`stripActiveAttributes`). */
    private fun keeps(attribute: HtmlTokenizer.Attribute, bytes: ByteArray): Boolean {
        val name = attribute.name
        if (name.startsWith("on")) return false
        if (name in setOf("action", "formaction", "nonce", "srcdoc")) return false
        if (isLinkTarget(name)) {
            // A link into the document itself (`#id`: an SVG <use>, a gradient, an in-page
            // anchor) stays; any other would navigate.
            val target = attribute.valueRange?.let { HtmlEntities.decode(string(bytes, it)) }.orEmpty()
            return target.trimStart().startsWith("#")
        }
        return true
    }

    /** `href` under any name — `href`, `xlink:href` (`/(?:^|:)href$/i`). */
    fun isLinkTarget(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "href" || lower.endsWith(":href")
    }

    /** The decoded value of the tag's first [name] attribute. */
    private fun value(name: String, tag: HtmlTokenizer.Tag, bytes: ByteArray): String? {
        val attribute = tag.attributes.firstOrNull { it.name == name } ?: return null
        return attribute.valueRange?.let { HtmlEntities.decode(string(bytes, it)) } ?: ""
    }

    /**
     * The index just past the element [tag] (at [start]) starts: itself for a void or
     * self-closed one, its raw text and end tag for a raw-text one, else up to its matching
     * end tag (or the end).
     */
    private fun elementEnd(tokens: List<HtmlTokenizer.Token>, start: Int, tag: HtmlTokenizer.Tag, removing: Boolean): Int {
        val end = tokens.size
        if (tag.name in VOID_ELEMENTS) return start + 1
        if (tag.name in HtmlTokenizer.RAW_TEXT_ELEMENTS) {
            var next = start + 1
            if (next < end && tokens[next] is HtmlTokenizer.Token.RawText) next += 1
            val close = tokens.getOrNull(next)
            if (close is HtmlTokenizer.Token.EndTag && close.name == tag.name) next += 1
            return next
        }
        // SVG's self-closed animation elements; an HTML element ignores the slash, so a
        // removed one takes its content with it.
        val isSvgAnimation = tag.name == "set" || tag.name == "animate"
        if (tag.selfClosing && (isSvgAnimation || !removing)) return start + 1
        var depth = 1
        var index = start + 1
        while (index < end) {
            when (val token = tokens[index]) {
                is HtmlTokenizer.Token.StartTag ->
                    if (token.tag.name == tag.name && !(token.tag.selfClosing && isSvgAnimation)) depth += 1
                is HtmlTokenizer.Token.EndTag -> if (token.name == tag.name) {
                    depth -= 1
                    if (depth == 0) return index + 1
                }
                else -> Unit
            }
            index += 1
        }
        return end
    }

    /** Where the head ends in a source without `<body>`. */
    private fun headSplit(tokens: List<HtmlTokenizer.Token>, bytes: ByteArray): Int {
        var index = 0
        while (index < tokens.size) {
            when (val token = tokens[index]) {
                // A stray end tag makes no node of its own.
                is HtmlTokenizer.Token.Comment, is HtmlTokenizer.Token.EndTag -> index += 1
                is HtmlTokenizer.Token.Text -> {
                    if (string(bytes, token.range).isNotBlank()) return index
                    index += 1
                }
                is HtmlTokenizer.Token.StartTag ->
                    if (token.tag.name in METADATA_ELEMENTS) {
                        index = elementEnd(tokens, index, token.tag, removing = false)
                    } else {
                        return index
                    }
                else -> return index
            }
        }
        return index
    }

    // endregion

    // region Serializing

    private fun serialize(tokens: List<HtmlTokenizer.Token>, bytes: ByteArray): String {
        val out = ByteArrayOutputStream(bytes.size)
        fun copy(range: IntRange) {
            if (!range.isEmpty()) out.write(bytes, range.first, range.last - range.first + 1)
        }
        for (token in tokens) {
            when (token) {
                is HtmlTokenizer.Token.Text -> copy(token.range)
                is HtmlTokenizer.Token.Comment -> copy(token.range)
                is HtmlTokenizer.Token.Doctype -> copy(token.range)
                is HtmlTokenizer.Token.RawText -> copy(token.range)
                is HtmlTokenizer.Token.EndTag -> copy(token.range)
                is HtmlTokenizer.Token.StartTag -> {
                    val tag = token.tag
                    out.write('<'.code)
                    copy(tag.nameRange)
                    for (attribute in tag.attributes) {
                        out.write(' '.code)
                        copy(attribute.range)
                    }
                    // Spaced, so an unquoted last value doesn't swallow the slash.
                    if (tag.selfClosing) {
                        out.write(' '.code)
                        out.write('/'.code)
                    }
                    out.write('>'.code)
                }
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun string(bytes: ByteArray, range: IntRange): String =
        if (range.isEmpty()) "" else String(bytes, range.first, range.last - range.first + 1, Charsets.UTF_8)

    // endregion
}

/**
 * Start tags (with their attributes), end tags, comments, doctypes, text and raw text, on
 * the HTML tokenizer's rules: attribute quoting, `<!-->`-style comment ends, `--!>`, bogus
 * comments (`<!x>`, `<?x>`, `</ x>`), and the raw text of `script`/`style`/`title`/
 * `textarea`/`xmp`/`iframe`/`noembed`/`noframes` (to their own end tag) and `plaintext`
 * (to the end). A tag the input ends inside is dropped, as the tokenizer drops it. Byte
 * offsets are into the source's UTF-8; every markup character is ASCII, so each range cuts
 * the text at character boundaries. Ranges are inclusive [IntRange]s; an empty one is
 * `start until start`.
 *
 * Not a tree builder: foreign content (SVG/MathML) switches nothing here.
 */
object HtmlTokenizer {
    data class Attribute(
        /** ASCII-lowercased. */
        val name: String,
        /** The value as written, without its quotes; null when there is none. */
        val valueRange: IntRange?,
        /** The attribute as written, name through value. */
        val range: IntRange,
    )

    data class Tag(
        /** ASCII-lowercased. */
        val name: String,
        val nameRange: IntRange,
        val attributes: List<Attribute>,
        val selfClosing: Boolean,
        /** `<` through `>`. */
        val range: IntRange,
    )

    sealed interface Token {
        data class Text(val range: IntRange) : Token
        data class Comment(val range: IntRange) : Token
        data class Doctype(val range: IntRange) : Token
        data class StartTag(val tag: Tag) : Token
        data class EndTag(val name: String, val range: IntRange) : Token
        data class RawText(val range: IntRange) : Token
    }

    val RAW_TEXT_ELEMENTS = setOf("script", "style", "title", "textarea", "xmp", "iframe", "noembed", "noframes")

    private const val LT = 0x3C.toByte()
    private const val GT = 0x3E.toByte()
    private const val SLASH = 0x2F.toByte()
    private const val BANG = 0x21.toByte()
    private const val QUESTION = 0x3F.toByte()
    private const val DASH = 0x2D.toByte()
    private const val EQUALS = 0x3D.toByte()
    private const val DOUBLE_QUOTE = 0x22.toByte()
    private const val SINGLE_QUOTE = 0x27.toByte()

    fun tokenize(html: String): List<Token> = tokenize(html.toByteArray(Charsets.UTF_8))

    fun tokenize(s: ByteArray): List<Token> {
        val tokens = ArrayList<Token>()
        val n = s.size
        var i = 0
        var textStart = 0
        fun flushText(end: Int) {
            if (end > textStart) tokens.add(Token.Text(textStart until end))
        }

        scan@ while (i < n) {
            val open = indexOf(LT, s, i) ?: break
            val next: Byte? = if (open + 1 < n) s[open + 1] else null

            if (next == BANG && hasPrefix(s, open, "<!--")) {
                flushText(open)
                val end = commentEnd(s, open)
                tokens.add(Token.Comment(open until end))
                i = end
                textStart = end
                continue
            }
            if (next == BANG || next == QUESTION) {
                flushText(open)
                val end = indexOf(GT, s, open + 2)?.let { it + 1 } ?: n
                if (next == BANG && hasPrefixIgnoringCase(s, open + 2, "doctype")) {
                    tokens.add(Token.Doctype(open until end))
                } else {
                    tokens.add(Token.Comment(open until end))
                }
                i = end
                textStart = end
                continue
            }
            if (next == SLASH) {
                val after = open + 2
                if (after >= n) break@scan // "</" at the end is text
                if (isAlpha(s[after])) {
                    flushText(open)
                    val (name, nameEnd) = readName(s, after)
                    val parsed = readAttributes(s, nameEnd)
                    if (parsed == null) {
                        textStart = n
                        break@scan
                    }
                    tokens.add(Token.EndTag(name, open until parsed.end))
                    i = parsed.end
                    textStart = parsed.end
                    continue
                }
                flushText(open)
                if (s[after] == GT) { // "</>" is nothing
                    i = after + 1
                    textStart = i
                    continue
                }
                val end = indexOf(GT, s, after)?.let { it + 1 } ?: n
                tokens.add(Token.Comment(open until end))
                i = end
                textStart = end
                continue
            }
            if (next != null && isAlpha(next)) {
                val (name, nameEnd) = readName(s, open + 1)
                val parsed = readAttributes(s, nameEnd)
                if (parsed == null) {
                    flushText(open)
                    textStart = n
                    break@scan
                }
                val tagEnd = parsed.end
                flushText(open)
                tokens.add(Token.StartTag(Tag(name, (open + 1) until nameEnd, parsed.attributes, parsed.selfClosing, open until tagEnd)))
                i = tagEnd
                textStart = tagEnd
                if (name == "plaintext") {
                    if (tagEnd < n) tokens.add(Token.RawText(tagEnd until n))
                    textStart = n
                    break@scan
                }
                if (name in RAW_TEXT_ELEMENTS) {
                    val close = rawTextEnd(s, name, tagEnd) ?: n
                    if (close > tagEnd) tokens.add(Token.RawText(tagEnd until close))
                    i = close
                    textStart = close
                }
                continue
            }
            // A "<" that starts nothing is text.
            i = open + 1
        }
        flushText(n)
        return tokens
    }

    /** The tag name from [start]: up to whitespace, `/` or `>`; lowercased. */
    private fun readName(s: ByteArray, start: Int): Pair<String, Int> {
        var i = start
        while (i < s.size && !isSpace(s[i]) && s[i] != SLASH && s[i] != GT) i += 1
        return lowercased(s, start, i) to i
    }

    private class ParsedAttributes(val attributes: List<Attribute>, val selfClosing: Boolean, val end: Int)

    /** The attributes from [start] to the tag's `>`: null when the input ends first. */
    private fun readAttributes(s: ByteArray, start: Int): ParsedAttributes? {
        val attributes = ArrayList<Attribute>()
        val n = s.size
        var i = start
        while (true) {
            while (i < n && isSpace(s[i])) i += 1
            if (i >= n) return null
            if (s[i] == GT) return ParsedAttributes(attributes, false, i + 1)
            if (s[i] == SLASH) {
                i += 1
                if (i < n && s[i] == GT) return ParsedAttributes(attributes, true, i + 1)
                continue
            }
            val nameStart = i
            // The first character always belongs to the name, even "=".
            i += 1
            while (i < n && !isSpace(s[i]) && s[i] != SLASH && s[i] != GT && s[i] != EQUALS) i += 1
            val nameEnd = i
            var j = i
            while (j < n && isSpace(s[j])) j += 1
            var valueRange: IntRange? = null
            var attributeEnd = nameEnd
            if (j < n && s[j] == EQUALS) {
                j += 1
                while (j < n && isSpace(s[j])) j += 1
                if (j >= n) return null
                val quote = s[j]
                if (quote == DOUBLE_QUOTE || quote == SINGLE_QUOTE) {
                    val close = indexOf(quote, s, j + 1) ?: return null
                    valueRange = (j + 1) until close
                    attributeEnd = close + 1
                } else if (quote == GT) {
                    valueRange = j until j
                    attributeEnd = j
                } else {
                    val valueStart = j
                    while (j < n && !isSpace(s[j]) && s[j] != GT) j += 1
                    valueRange = valueStart until j
                    attributeEnd = j
                }
                i = attributeEnd
            }
            attributes.add(Attribute(lowercased(s, nameStart, nameEnd), valueRange, nameStart until attributeEnd))
        }
    }

    /** Where a raw-text element's content ends: at `</name` + whitespace, `/` or `>`. */
    private fun rawTextEnd(s: ByteArray, name: String, start: Int): Int? {
        val nameLength = name.length
        val n = s.size
        var i = start
        while (true) {
            val open = indexOf(LT, s, i) ?: return null
            val after = open + 2 + nameLength
            if (open + 1 < n && s[open + 1] == SLASH && after < n &&
                hasPrefixIgnoringCase(s, open + 2, name) &&
                (isSpace(s[after]) || s[after] == SLASH || s[after] == GT)
            ) {
                return open
            }
            i = open + 1
        }
    }

    /**
     * The end of the comment that opens at [start] ("<!--"): after `-->`, `--!>`, or the
     * abrupt `<!-->` / `<!--->`; the input's end otherwise.
     */
    private fun commentEnd(s: ByteArray, start: Int): Int {
        val n = s.size
        val body = start + 4
        if (body < n && s[body] == GT) return body + 1
        if (body + 1 < n && s[body] == DASH && s[body + 1] == GT) return body + 2
        var i = body
        while (i + 2 < n) {
            if (s[i] == DASH && s[i + 1] == DASH) {
                if (s[i + 2] == GT) return i + 3
                if (i + 3 < n && s[i + 2] == BANG && s[i + 3] == GT) return i + 4
            }
            i += 1
        }
        return n
    }

    private fun indexOf(byte: Byte, s: ByteArray, start: Int): Int? {
        var i = start
        while (i < s.size) {
            if (s[i] == byte) return i
            i += 1
        }
        return null
    }

    private fun hasPrefix(s: ByteArray, start: Int, prefix: String): Boolean {
        if (start + prefix.length > s.size) return false
        for (k in prefix.indices) if (s[start + k] != prefix[k].code.toByte()) return false
        return true
    }

    private fun hasPrefixIgnoringCase(s: ByteArray, start: Int, prefix: String): Boolean {
        if (start + prefix.length > s.size) return false
        for (k in prefix.indices) if (lower(s[start + k]) != lower(prefix[k].code.toByte())) return false
        return true
    }

    private fun isSpace(b: Byte): Boolean = b == 0x20.toByte() || b == 0x09.toByte() || b == 0x0A.toByte() ||
        b == 0x0C.toByte() || b == 0x0D.toByte()

    private fun isAlpha(b: Byte): Boolean = b in 0x41..0x5A || b in 0x61..0x7A

    private fun lower(b: Byte): Byte = if (b in 0x41..0x5A) (b.toInt() or 0x20).toByte() else b

    private fun lowercased(s: ByteArray, start: Int, end: Int): String {
        val copy = ByteArray(end - start) { lower(s[start + it]) }
        return String(copy, Charsets.UTF_8)
    }
}

/**
 * Character references in an attribute value, decoded far enough to judge what it says:
 * numeric ones and the common named ones. Used only to test a value (a fragment link, a
 * refresh `<meta>`), never to rewrite one.
 */
object HtmlEntities {
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "num" to "#", "colon" to ":", "sol" to "/", "tab" to "\t", "newline" to "\n", "hyphen" to "-", "dash" to "-",
        "period" to ".", "comma" to ",", "lpar" to "(", "rpar" to ")", "equals" to "=",
    )

    fun decode(text: String): String {
        if ('&' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val amp = text.indexOf('&', i)
            if (amp < 0) {
                out.append(text, i, text.length)
                break
            }
            out.append(text, i, amp)
            val found = reference(text, amp + 1)
            if (found != null) {
                out.append(found.first)
                i = found.second
            } else {
                out.append('&')
                i = amp + 1
            }
        }
        return out.toString()
    }

    /** The reference at [start] (just after its `&`) and the index where it ends. */
    private fun reference(s: String, start: Int): Pair<String, Int>? {
        if (start < s.length && s[start] == '#') {
            var digitsStart = start + 1
            var radix = 10
            if (digitsStart < s.length && (s[digitsStart] == 'x' || s[digitsStart] == 'X')) {
                radix = 16
                digitsStart += 1
            }
            var end = digitsStart
            while (end < s.length && (if (radix == 16) s[end].isAsciiHexDigit() else s[end] in '0'..'9')) end += 1
            if (end == digitsStart) return null
            val value = s.substring(digitsStart, end).toLongOrNull(radix) ?: return null
            if (value > 0xFFFFFFFFL) return null
            if (end < s.length && s[end] == ';') end += 1
            val code = value.toInt()
            val text = if (value <= 0x10FFFF && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF) {
                String(Character.toChars(code))
            } else {
                "\uFFFD"
            }
            return text to end
        }
        var end = start
        while (end < s.length && s[end].isLetterOrDigit()) end += 1
        val decoded = NAMED[s.substring(start, end).lowercase()] ?: return null
        if (end < s.length && s[end] == ';') end += 1
        return decoded to end
    }

    private fun Char.isAsciiHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

/** An sRGB color as 0–255 channels, for the documents' CSS. */
data class Rgb(val red: Int, val green: Int, val blue: Int) {
    val css: String get() = "rgb($red,$green,$blue)"

    companion object {
        /** From a packed `0xAARRGGBB` (Compose's `Color.toArgb()`). */
        fun ofArgb(argb: Int) = Rgb((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }
}

/**
 * The surface diagram and HTML pictures are drawn on, and the inline card behind them:
 * the theme's `bgElevated` — white in light mode, a lifted near-black in dark — so a
 * picture and its card are one color.
 */
object RichBlockPalette {
    fun surface(dark: Boolean): Rgb = if (dark) Rgb(0x14, 0x16, 0x19) else Rgb(0xFF, 0xFF, 0xFF)
}

/**
 * The theme a preview is drawn in: the color scheme and the accent (its fill and the color
 * on it). Part of every cache key.
 */
data class ChatHtmlTheme(
    val dark: Boolean,
    val accentId: String,
    val primary: Rgb,
    val onPrimary: Rgb,
) {
    val key: String get() = "${if (dark) "dark" else "light"}-$accentId"

    /** The surface the document is drawn on — the inline card's color. */
    val surface: Rgb get() = RichBlockPalette.surface(dark)

    /**
     * The theme tokens codeg shares with the Codex visualize skill's contract
     * (`readHtmlPreviewTheme`): the web's default (neutral) preset, with the surface as
     * `--background` and the app's accent as `--primary`/`--ring`.
     */
    val variablesCss: String
        get() {
            val primaryCss = primary.css
            val tokens = mutableListOf("background" to surface.css)
            tokens += if (dark) {
                listOf(
                    "foreground" to "oklch(0.985 0 0)",
                    "card" to "oklch(0.205 0 0)",
                    "card-foreground" to "oklch(0.985 0 0)",
                    "popover" to "oklch(0.205 0 0)",
                    "popover-foreground" to "oklch(0.985 0 0)",
                    "primary" to primaryCss,
                    "primary-foreground" to onPrimary.css,
                    "secondary" to "oklch(0.269 0 0)",
                    "secondary-foreground" to "oklch(0.985 0 0)",
                    "muted" to "oklch(0.269 0 0)",
                    "muted-foreground" to "oklch(0.708 0 0)",
                    "accent" to "oklch(0.371 0 0)",
                    "accent-foreground" to "oklch(0.985 0 0)",
                    "destructive" to "oklch(0.704 0.191 22.216)",
                    "border" to "oklch(1 0 0 / 10%)",
                    "input" to "oklch(1 0 0 / 15%)",
                    "ring" to primaryCss,
                )
            } else {
                listOf(
                    "foreground" to "oklch(0.145 0 0)",
                    "card" to "oklch(1 0 0)",
                    "card-foreground" to "oklch(0.145 0 0)",
                    "popover" to "oklch(1 0 0)",
                    "popover-foreground" to "oklch(0.145 0 0)",
                    "primary" to primaryCss,
                    "primary-foreground" to onPrimary.css,
                    "secondary" to "oklch(0.97 0 0)",
                    "secondary-foreground" to "oklch(0.205 0 0)",
                    "muted" to "oklch(0.97 0 0)",
                    "muted-foreground" to "oklch(0.556 0 0)",
                    "accent" to "oklch(0.97 0 0)",
                    "accent-foreground" to "oklch(0.205 0 0)",
                    "destructive" to "oklch(0.58 0.22 27)",
                    "border" to "oklch(0.922 0 0)",
                    "input" to "oklch(0.922 0 0)",
                    "ring" to primaryCss,
                )
            }
            val declarations = tokens.map { "--${it.first}:${it.second}" } +
                // The skill aliases its first chart series to `--primary`; keep its own blue
                // for charts, as the web does.
                "--viz-series-1:var(--blue)"
            return ":root{${declarations.joinToString(";")}}"
        }
}

/**
 * The preview's heights (`html-code-preview.tsx`): the document's measured height clamped to
 * 96–12,000 dp, 160 before it is known, and at most 640 shown until "Show all".
 */
object HtmlPreviewMetrics {
    const val MIN_HEIGHT = 96f
    const val MAX_HEIGHT = 12_000f
    const val PLACEHOLDER_HEIGHT = 160f
    const val COLLAPSED_HEIGHT = 640f

    /**
     * A measured height as the preview uses it. Nothing to measure (0, or not a number)
     * leaves the default height, as on the web, where the frame keeps it until a height is
     * reported.
     */
    fun clamp(measured: Double): Float {
        if (!measured.isFinite() || measured <= 0) return PLACEHOLDER_HEIGHT
        return kotlin.math.ceil(measured).toFloat().coerceIn(MIN_HEIGHT, MAX_HEIGHT)
    }

    /** Taller than the collapsed view: offers "Show all". */
    fun overflows(height: Float): Boolean = height > COLLAPSED_HEIGHT

    /** How much of a document [height] tall is shown. */
    fun visibleHeight(height: Float, expanded: Boolean): Float =
        if (expanded || !overflows(height)) height else COLLAPSED_HEIGHT

    /** How much of the document the picture covers from the top. */
    fun pictureClip(expanded: Boolean): Float = if (expanded) MAX_HEIGHT else COLLAPSED_HEIGHT
}

/**
 * The view the reader picked per source — Code or Preview, collapsed or all — so a preview
 * drawn again as a new row (as a reply settles, or a row is recycled) comes back as it was
 * (the web's `views` map: 32 entries / 1 MB of keys). Main-thread only.
 */
class HtmlPreviewViewMemory {
    data class Choice(val preview: Boolean = true, val expanded: Boolean = false)

    private val entries = LinkedHashMap<String, Choice>()
    var bytes: Int = 0
        private set

    val count: Int get() = entries.size

    fun choice(source: String): Choice = entries[source] ?: Choice()

    fun remember(choice: Choice, source: String) {
        val cost = cost(source)
        if (entries.remove(source) != null) bytes -= cost
        // A view preference alone should never pin a multi-megabyte source.
        if (cost > MAX_BYTES) return
        entries[source] = choice
        bytes += cost
        val it = entries.keys.iterator()
        while ((entries.size > LIMIT || bytes > MAX_BYTES) && it.hasNext()) {
            val oldest = it.next()
            bytes -= cost(oldest)
            it.remove()
        }
    }

    companion object {
        const val LIMIT = 32
        const val MAX_BYTES = 1024 * 1024

        val shared = HtmlPreviewViewMemory()

        private fun cost(source: String) = 64 + source.length * 2
    }
}
