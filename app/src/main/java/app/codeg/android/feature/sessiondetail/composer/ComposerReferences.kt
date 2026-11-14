package app.codeg.android.feature.sessiondetail.composer

import app.codeg.android.core.model.AgentType

/**
 * A mention the user picked from the `@` panel, as the composer keeps it.
 *
 * The field shows a readable token (`@docs/README.md`, `@Codex`); the prompt carries the web's
 * wire form — a plain-text Markdown link inside the text (`[README.md](file:///…/docs/README.md)`,
 * `[@Codex](codeg://agent/codex)`). That link is what the web transcript turns back into a chip,
 * and the agent one is what the backend routes a delegation by (`agent_mentions.rs`), so it is
 * built exactly the way the web's `referenceToMarkdown` builds it.
 */
data class ComposerReference(
    /** The text the field shows, `@` included. Matched exactly when encoding. */
    val token: String,
    val kind: Kind,
    /** The link text without the agent's `@`: a file's name, an agent's name. */
    val label: String,
    /** `file://…` or `codeg://agent/<agent_type>`. */
    val uri: String,
) {
    enum class Kind { FILE, AGENT }

    /** The inline wire form (web `referenceToMarkdown`). */
    val markdown: String
        get() {
            val text = ComposerReferenceCodec.escapeMarkdownText(ComposerReferenceCodec.collapseNewlines(label))
            val destination = ComposerReferenceCodec.escapeLinkDestination(uri)
            return when (kind) {
                Kind.FILE -> "[$text]($destination)"
                Kind.AGENT -> "[@$text]($destination)"
            }
        }

    companion object {
        /** A workspace file or directory (web `fileToSuggestion`): the token is the root-relative
         *  path, the link text the entry's name. */
        fun file(name: String, relativePath: String, workspaceRoot: String): ComposerReference =
            ComposerReference(
                token = "@$relativePath",
                kind = Kind.FILE,
                label = name.ifEmpty { relativePath },
                uri = ComposerReferenceCodec.buildFileUri(ComposerReferenceCodec.joinPath(workspaceRoot, relativePath)),
            )

        /** An agent (web `agentToSuggestion`): `codeg://agent/<agent_type>` is the routing anchor
         *  the backend derives the delegation reminder from. */
        fun agent(agentType: AgentType, label: String): ComposerReference {
            val name = label.ifEmpty { agentType.wire }
            return ComposerReference("@$name", Kind.AGENT, name, "codeg://agent/${agentType.wire}")
        }
    }
}

/**
 * Turns the field's readable mention tokens into the web's Markdown links on the way out, and
 * links back into tokens when a sent message returns to the composer (a rejected send, a queued
 * message taken back for editing).
 *
 * The escape and URI builders are exact ports of web `reference-text.ts` and
 * `src/lib/reference-link.ts` (via iOS `ComposerReferences.swift`), so a mention sent from here
 * renders as a chip in the web transcript. All scanning is in UTF-16 code units, like the web.
 */
object ComposerReferenceCodec {

    // region Encode

    /**
     * Replace every registered token in [text] with its Markdown link.
     *
     * A token counts only where the user could have inserted it — where a mention may start
     * ([MentionStart]) — and only when it ends at a boundary: the end, whitespace, CJK text or
     * closing punctuation, or a sentence-ending `.`. A token the user edited (`@README.md` →
     * `@README.mdx`) therefore no longer matches and goes out as the plain text it now is. Longer
     * tokens are tried first, so `@a b.md` wins over `@a`.
     */
    fun encode(text: String, references: List<ComposerReference>): String {
        if (references.isEmpty() || '@' !in text) return text
        val ordered = references
            .filter { it.token.isNotEmpty() }
            .distinctBy { it.token }
            .sortedByDescending { it.token.length }
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '@' && MentionStart.canStart(text, i)) {
                val hit = ordered.firstOrNull { matches(it.token, text, i) }
                if (hit != null) {
                    out.append(hit.markdown)
                    i += hit.token.length
                    continue
                }
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    private fun matches(token: String, text: String, i: Int): Boolean =
        text.startsWith(token, i) && endsToken(text, i + token.length)

    private const val CLOSING_PUNCTUATION = ",;:!?)]}>\"'"

    /** Whether a token ending at [end] stops there rather than running on into what follows. */
    internal fun endsToken(text: CharSequence, end: Int): Boolean {
        if (end >= text.length) return true
        val next = text[end]
        if (JsWhitespace.contains(next)) return true
        // A sentence-ending period, not a longer file name.
        if (next == '.') return end + 1 == text.length || JsWhitespace.contains(text[end + 1])
        if (next in CLOSING_PUNCTUATION) return true
        // CJK text runs straight on without a space; the same set that may precede a mention
        // may follow one.
        if (next.isHighSurrogate()) {
            if (end + 1 >= text.length || !text[end + 1].isLowSurrogate()) return false
            return MentionStart.isAllowedPrefix(Character.toCodePoint(next, text[end + 1]))
        }
        if (next.code <= 0x7F || next.isLowSurrogate()) return false
        return MentionStart.isAllowedPrefix(next.code)
    }

    // endregion

    // region Decode

    /** A decoded message: the text the field shows and the references to register for it. */
    data class Decoded(val text: String, val references: List<ComposerReference>)

    /**
     * Turn the links a sent message carries back into tokens.
     *
     * Only what the composer itself produces is folded: a `file://` link to a path under
     * [workspaceRoot], and a `codeg://agent/…` link. Anything else — and any link whose token
     * would not survive the next [encode] at that spot, e.g. one glued to the word before it —
     * stays as written, so restoring and re-sending reproduces the original text.
     */
    fun decode(text: String, workspaceRoot: String?): Decoded {
        val tokens = tokenizeReferenceLinks(text)
        if (tokens.none { it is LinkToken.Link }) return Decoded(text, emptyList())
        val out = StringBuilder()
        val references = ArrayList<ComposerReference>()
        for ((index, token) in tokens.withIndex()) {
            when (token) {
                is LinkToken.Text -> out.append(token.value)
                is LinkToken.Link -> {
                    // The character that will follow the token: the next prose run's first one,
                    // or the end. A link right after is never a boundary (it opens with `[`, or
                    // `@` once folded) — which also holds for a link right after that prose run.
                    val followerOk = if (index + 1 < tokens.size) {
                        val next = tokens[index + 1]
                        if (next is LinkToken.Text) {
                            val follow = if (index + 2 < tokens.size) next.value + "[" else next.value
                            endsToken(follow, 0)
                        } else {
                            false
                        }
                    } else {
                        true
                    }
                    val folded = if (followerOk && MentionStart.canStart(out, out.length)) {
                        foldedReference(
                            unescapeReferenceLabel(token.label),
                            unwrapReferenceDestination(token.destination),
                            workspaceRoot,
                        )
                    } else {
                        null
                    }
                    if (folded != null) {
                        out.append(folded.token)
                        references.add(folded)
                    } else {
                        out.append(token.raw)
                    }
                }
            }
        }
        return Decoded(out.toString(), references)
    }

    private const val AGENT_SCHEME = "codeg://agent/"

    private fun foldedReference(label: String, destination: String, workspaceRoot: String?): ComposerReference? {
        val lower = destination.lowercase()
        if (lower.startsWith("file:")) {
            if (workspaceRoot.isNullOrEmpty()) return null
            val relative = relativePath(destination, workspaceRoot) ?: return null
            return ComposerReference("@$relative", ComposerReference.Kind.FILE, label.ifEmpty { relative }, destination)
        }
        if (lower.startsWith(AGENT_SCHEME)) {
            val type = destination.substring(AGENT_SCHEME.length)
            if (type.isEmpty()) return null
            // The link text is `@name`; one leading `@` is the mention's own.
            val name = label.removePrefix("@").ifEmpty { type }
            return ComposerReference("@$name", ComposerReference.Kind.AGENT, name, destination)
        }
        return null
    }

    /** The root-relative path a `file://` [uri] points at, when it is exactly what
     *  [buildFileUri] makes of [root] joined with that path. */
    fun relativePath(uri: String, root: String): String? {
        val base = buildFileUri(joinPath(root, ""))
        val baseWithSlash = if (base.endsWith("/")) base else "$base/"
        if (!uri.startsWith(baseWithSlash)) return null
        val rest = uri.substring(baseWithSlash.length)
        if (rest.isEmpty()) return null
        val segments = rest.split('/').map { percentDecode(it) ?: return null }
        val relative = segments.joinToString("/")
        // Only a URI the builder would produce again folds; anything else (a fragment, odd
        // encoding) keeps its link.
        if (buildFileUri(joinPath(root, relative)) != uri) return null
        return relative
    }

    /** `decodeURIComponent`, or null for a malformed escape / invalid UTF-8. */
    private fun percentDecode(segment: String): String? {
        if ('%' !in segment) return segment
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '%') {
                if (i + 3 > segment.length) return null
                val digits = segment.substring(i + 1, i + 3)
                // `toIntOrNull(16)` would take a sign ("%+5"); a percent escape is two hex digits.
                if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                val hex = digits.toInt(16)
                bytes.write(hex)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString() }.getOrNull()
    }

    // endregion

    // region Display

    /** Every reference link folded to its label (web `foldReferenceLinks`), for one-line text
     *  such as a new conversation's title seed or a queued message's preview. */
    fun foldLinks(text: String): String {
        if ('[' !in text) return text
        val out = StringBuilder()
        for (token in tokenizeReferenceLinks(text)) {
            when (token) {
                is LinkToken.Text -> out.append(token.value)
                is LinkToken.Link -> out.append(unescapeReferenceLabel(token.label))
            }
        }
        return out.toString()
    }

    // endregion

    // region Web reference-text.ts

    /** Collapse each whitespace run holding a line break into one space, so a reference stays
     *  one inline token (the web's `\s*[\r\n]+\s*` → `" "`, global). */
    fun collapseNewlines(text: String): String {
        if ('\n' !in text && '\r' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (!JsWhitespace.contains(text[i])) {
                out.append(text[i])
                i++
                continue
            }
            var j = i
            var hasBreak = false
            while (j < text.length && JsWhitespace.contains(text[j])) {
                if (text[j] == '\n' || text[j] == '\r') hasBreak = true
                j++
            }
            if (hasBreak) out.append(' ') else out.append(text, i, j)
            i = j
        }
        return out.toString()
    }

    private const val MARKDOWN_SIGNIFICANT = "\\`*_~[]()<>"

    /** Backslash-escape every inline-significant ASCII punctuation character
     *  (``\ ` * _ ~ [ ] ( ) < >``) so a label cannot inject Markdown structure. */
    fun escapeMarkdownText(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            if (c in MARKDOWN_SIGNIFICANT) out.append('\\')
            out.append(c)
        }
        return out.toString()
    }

    /**
     * A Markdown link destination: wrapped in `<…>` (with `\`, `<`, `>` escaped inside) when it
     * holds whitespace, parentheses, angle brackets or a backslash — so a `)` or trailing `\`
     * cannot end the link early. Line breaks are stripped. Clean URIs stay bare.
     */
    fun escapeLinkDestination(uri: String): String {
        val cleaned = uri.filterNot { it == '\r' || it == '\n' }
        val needsWrap = cleaned.any { JsWhitespace.contains(it) || it == '(' || it == ')' || it == '<' || it == '>' || it == '\\' }
        if (!needsWrap) return cleaned
        val inner = StringBuilder(cleaned.length + 2)
        for (c in cleaned) {
            if (c == '\\' || c == '<' || c == '>') inner.append('\\')
            inner.append(c)
        }
        return "<$inner>"
    }

    // endregion

    // region Web reference-link.ts

    /** Web `joinPath` (adapters.ts): one `/` between root and relative path. */
    fun joinPath(root: String, relative: String): String {
        val left = root.trimEnd('/', '\\')
        val right = relative.trimStart('/', '\\')
        return if (left.isEmpty()) right else "$left/$right"
    }

    /**
     * A `file://` URI from an absolute path (POSIX or Windows), each segment percent-encoded like
     * `encodeURIComponent`: `/a/b` → `file:///a/b`, a drive path → `file:///C%3A/…`, a UNC path
     * → `file://server/share/…`.
     */
    fun buildFileUri(absolutePath: String): String {
        val normalized = stripVerbatimPrefix(absolutePath.replace('\\', '/'))
        if (normalized.startsWith("//")) return "file://" + encodeSegments(normalized.substring(2))
        val encoded = encodeSegments(normalized)
        return if (normalized.startsWith("/")) "file://$encoded" else "file:///$encoded"
    }

    private fun encodeSegments(path: String): String =
        path.split('/').joinToString("/") { encodeURIComponent(it) }

    /** Drop a Windows verbatim prefix (`//?/C:/…`, `//?/UNC/srv/share/…`) from an already
     *  slash-normalized path; device paths are left alone. */
    private fun stripVerbatimPrefix(normalized: String): String {
        val uncPrefix = "//?/UNC/"
        if (normalized.length >= uncPrefix.length && normalized.substring(0, uncPrefix.length).uppercase() == uncPrefix) {
            return "//" + normalized.substring(uncPrefix.length)
        }
        if (normalized.startsWith("//?/")) {
            val rest = normalized.substring(4)
            if (rest.length >= 2 && rest[1] == ':' && rest[0].code < 0x80 && rest[0].isLetter()) return rest
        }
        return normalized
    }

    private const val URI_UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
    private const val HEX = "0123456789ABCDEF"

    /** JS `encodeURIComponent`: everything but `A–Z a–z 0–9 - _ . ! ~ * ' ( )` becomes `%XX`
     *  over its UTF-8 bytes. */
    fun encodeURIComponent(text: String): String {
        val out = StringBuilder(text.length)
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            if (b < 0x80 && b.toChar() in URI_UNRESERVED) {
                out.append(b.toChar())
            } else {
                out.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
        }
        return out.toString()
    }

    /** Reverse [escapeMarkdownText]. */
    fun unescapeReferenceLabel(label: String): String = unescape(label, MARKDOWN_SIGNIFICANT)

    /** Reverse [escapeLinkDestination]: unwrap `<…>` and drop the backslashes it escapes `\`,
     *  `<` and `>` with. A bare destination is only trimmed. */
    fun unwrapReferenceDestination(destination: String): String {
        val trimmed = destination.trim()
        if (trimmed.length < 2 || !trimmed.startsWith("<") || !trimmed.endsWith(">")) return trimmed
        return unescape(trimmed.substring(1, trimmed.length - 1).trim(), "\\<>")
    }

    /** `s.replace(/\\([set])/g, "$1")`. */
    private fun unescape(text: String, set: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in set) {
                out.append(text[i + 1])
                i += 2
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** One prose run or one parsed `[label](destination)` link. */
    sealed interface LinkToken {
        data class Text(val value: String) : LinkToken

        /** [label] is still escaped; [destination] is as written, `<…>` included. */
        data class Link(val raw: String, val label: String, val destination: String) : LinkToken
    }

    /**
     * Web `tokenizeReferenceLinks`: a single forward scan over a stack of unmatched `[`. A `]`
     * closes the most recent `[`; followed by a well-formed `(dest)` with a non-empty label it is
     * a link. Escaped brackets never open or close. Every input unit lands in one token.
     */
    fun tokenizeReferenceLinks(text: String): List<LinkToken> {
        val n = text.length
        val tokens = ArrayList<LinkToken>()
        var textStart = 0
        val openers = ArrayList<Int>()
        var i = 0
        while (i < n) {
            if (escapesNext(text, i)) {
                i += 2
                continue
            }
            val c = text[i]
            if (c == '[') {
                openers.add(i)
                i++
                continue
            }
            if (c == ']' && openers.isNotEmpty()) {
                val open = openers.removeAt(openers.size - 1)
                val end = destinationEnd(text, i + 1)
                if (end != -1 && i > open + 1) {
                    if (open > textStart) tokens.add(LinkToken.Text(text.substring(textStart, open)))
                    tokens.add(LinkToken.Link(text.substring(open, end), text.substring(open + 1, i), text.substring(i + 2, end - 1)))
                    openers.clear()
                    i = end
                    textStart = end
                    continue
                }
                i++
                continue
            }
            i++
        }
        if (n > textStart) tokens.add(LinkToken.Text(text.substring(textStart, n)))
        return tokens
    }

    /** A backslash escapes the next unit only when that unit is not whitespace. */
    private fun escapesNext(s: String, k: Int): Boolean =
        s[k] == '\\' && k + 1 < s.length && !JsWhitespace.contains(s[k + 1])

    /** Index just past the `)` of a well-formed `(destination)` at [start], or -1. */
    private fun destinationEnd(s: String, start: Int): Int {
        val n = s.length
        if (start >= n || s[start] != '(') return -1
        var k = start + 1
        if (k < n && s[k] == '<') {
            k++
            while (k < n) {
                if (escapesNext(s, k)) {
                    k += 2
                    continue
                }
                val c = s[k]
                if (c == '>') return if (k + 1 < n && s[k + 1] == ')') k + 2 else -1
                if (c == '<' || c == '\n' || c == '\r') return -1
                k++
            }
            return -1
        }
        while (k < n) {
            if (escapesNext(s, k)) {
                k += 2
                continue
            }
            val c = s[k]
            if (c == ')') return k + 1
            if (c == '(' || c == '<' || c == '>' || JsWhitespace.contains(c)) return -1
            k++
        }
        return -1
    }

    // endregion
}
