package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * OpenCode code-mode delegation (codeg web 0.44 `lib/delegation-card.ts`, its `codeMode*`
 * scanner; the backend twin is `acp/lifecycle.rs`).
 *
 * OpenCode 2.x runs MCP tools through its built-in `execute` tool: the model writes
 * `tools["codeg-mcp"].delegate_to_agent({…})` as JavaScript packed into `{"code": "…"}`. The
 * backend registers such a call as a delegation, so the card must recognise exactly the same
 * calls, and read the agent, task and directory out of the script's object literal.
 *
 * The scanner is STRICT, as the backend's is: `tools` must reach `delegate_to_agent(` through
 * a real accessor; strings, templates and comments are skipped (a task that *quotes* a call is
 * not a call); every ambiguity degrades to "unreadable" rather than a guessed value. Offsets
 * are UTF-16 units — a Kotlin [String]'s, as they are JavaScript's — so the port is literal.
 */
internal object CodeModeDelegation {

    // region Gates

    /**
     * Whether [name] is a command tool's (web `normalizeToolName(name) === "bash"`), which is
     * what OpenCode's `execute` normalizes to, live and in history. The rest of the web's
     * normalizer can't reach `bash` for any other name, so these are its routes there.
     */
    fun normalizesToBash(name: String): Boolean {
        val trimmed = name.trim().trim(*QUOTES).trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()
        if (lower in BASH_ALIASES) return true
        if (lower in OTHER_ALIASES) return false
        val canonical = lower.replace(Regex("[().]"), "_").replace(Regex("[\\s-]+"), "_")
        if (canonical in BASH_ALIASES) return true
        if (canonical in OTHER_ALIASES) return false
        // An MCP server's tool keeps its own name; the freeform rule never sees one.
        if (lower.startsWith("mcp__")) return false
        return FREEFORM_BASH.containsMatchIn(lower)
    }

    private val QUOTES = charArrayOf(':', '：', '\'', '"', '`', '“', '”', '‘', '’')

    private val BASH_ALIASES = setOf(
        "bash", "execute", "shell", "shell_command", "functions.shell_command", "functions_shell_command",
        "run_terminal_command", "run_command", "powershell", "execute_command",
    )

    // Exact aliases the freeform rule would otherwise take for a command.
    private val OTHER_ALIASES = setOf(
        "exec_command", "functions.exec_command", "functions_exec_command", "codex_script", "wait", "write_stdin",
    )

    private val FREEFORM_BASH = Regex("\\b(?:shell|bash|exec(?:ute)?)\\s*[_-]?(?:command|cmd)?\\b")

    /**
     * Whether a raw tool input is an OpenCode code-mode payload whose `code` source announces a
     * `delegate_to_agent` call (web `isCodeModeDelegationInput`). Shape only: classify a call
     * with [isCall], which also checks the tool.
     */
    fun isInput(raw: String?): Boolean {
        // Cheap pre-gate, as the backend's: a payload carrying a `code` key has these bytes.
        if (raw.isNullOrEmpty() || !raw.contains("\"code\"")) return false
        return callOf(parse(raw)) != null
    }

    /**
     * Whether a call is OpenCode's code-mode `execute` running `delegate_to_agent` (web
     * `isCodeModeDelegationCall`) — so it draws as a delegation exactly when the backend
     * registers it. The tool gate keeps another tool's `code` argument (a Playwright script
     * that mocks the call) from passing for one.
     */
    fun isCall(name: String, raw: String?): Boolean = normalizesToBash(name) && isInput(raw)

    // endregion

    // region Arguments

    /**
     * The delegation's arguments read out of the script (web `parseCodeModeInput`): null when
     * [parsed] is no code-mode delegation at all; empty [DelegationParse.Args] when it is one
     * whose arguments can't be read — the argument is not exactly one object literal, or holds
     * syntax whose effect the scanner can't evaluate. Readable values become card content,
     * unreadable ones stay null field by field.
     */
    fun args(parsed: JsonElement?): DelegationParse.Args? {
        val (code, site) = callOf(parsed) ?: return null
        val start = site.open + 1
        if (!site.readable ||
            !singleObjectArgument(code, site.open, site.close) ||
            !argsTransparent(code, start, site.close)
        ) {
            return DelegationParse.Args()
        }
        val assistantAt = findKey(code, start, site.close, "assistant_id")
        val agentAt = findKey(code, start, site.close, "agent_type")
        val workingAt = findKey(code, start, site.close, "working_dir")
        val taskAt = findKey(code, start, site.close, "task")
        return DelegationParse.Args(
            agentType = agentAt?.let { coerceAgentType(objectStringValue(code, it)) },
            task = taskAt?.let { objectTaskValue(code, it) },
            workingDir = workingAt?.let { objectStringValue(code, it) },
            assistantId = assistantAt?.let { coerceAssistantId(readAssistantIdValue(code, it)) },
        )
    }

    // Every agent codeg 0.44 knows (web `ALL_AGENT_TYPES`).
    private val KNOWN_AGENT_TYPES = setOf(
        "claude_code", "codex", "open_code", "gemini", "open_claw", "cline", "hermes", "code_buddy",
        "kimi_code", "minimax_code", "pi", "grok", "cursor", "deepseek", "qoder", "antigravity",
        "kiro", "zcode", "trae", "command_code",
    )

    /**
     * An untrusted wire value narrowed to an agent type, or null (web `coerceAgentType`): a
     * built-in, or any `custom:<id>` slug. An unknown slug draws the generic sub-agent rather
     * than an agent named after it.
     */
    fun coerceAgentType(value: String?): String? {
        val trimmed = value?.let(::jsTrim)?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.takeIf { it in KNOWN_AGENT_TYPES || it.startsWith("custom:") }
    }

    /** Largest assistant id: the backend keeps them in a 32-bit integer. */
    private const val MAX_ASSISTANT_ID = 2_147_483_647L
    private val ASSISTANT_ID_TEXT = Regex("^[1-9][0-9]{0,9}$")

    /** A string of digits narrowed to an assistant id (web `coerceAssistantId`), or null. */
    fun coerceAssistantId(value: String?): Int? {
        val trimmed = value?.let(::jsTrim) ?: return null
        if (!ASSISTANT_ID_TEXT.matches(trimmed)) return null
        return trimmed.toLong().takeIf { it in 1..MAX_ASSISTANT_ID }?.toInt()
    }

    /** A wire value narrowed to an assistant id: a positive integer, or digits as text. */
    fun coerceAssistantId(value: JsonElement?): Int? {
        val p = value as? JsonPrimitive ?: return null
        if (p.isString) return coerceAssistantId(p.content)
        val n = p.content.toDoubleOrNull() ?: return null
        if (n.isNaN() || n.isInfinite() || n != Math.floor(n) || n < 1 || n > MAX_ASSISTANT_ID) return null
        return n.toLong().toInt()
    }

    // endregion

    // region Scanner

    private val strict = Json

    private fun parse(raw: String): JsonElement? = runCatching { strict.parseToJsonElement(raw) }.getOrNull()

    /** Scan cap in UTF-8 bytes, the unit the backend measures (`code.len()`). */
    private const val MAX_CODE_BYTES = 1 shl 20
    private const val MAX_TEMPLATE_FRAMES = 16

    private class Site(val open: Int, val close: Int, val readable: Boolean)

    /** JS `code[i]`: undefined past either end. */
    private fun String.at(i: Int): Char? = if (i in indices) this[i] else null

    /** The `code` source and its first call site, or null (backend `code_mode_delegation_code`). */
    private fun callOf(parsed: JsonElement?): Pair<String, Site>? {
        val obj = parsed as? JsonObject ?: return null
        val code = (obj["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val site = callSite(code) ?: return null
        return code to site
    }

    // Any non-ASCII unit counts as an identifier char: the scan only needs the boundaries of
    // ASCII words, and over-approximating can only reject a boundary, never mis-split one.
    private fun isIdentUnit(code: String, at: Int): Boolean {
        val c = code.at(at) ?: return false
        return c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z' || c == '_' || c == '$' || c.code >= 0x80
    }

    private fun isWordAt(code: String, at: Int, word: String): Boolean {
        if (at < 0 || at + word.length > code.length) return false
        if (!code.startsWith(word, at)) return false
        val after = at + word.length
        return (at == 0 || !isIdentUnit(code, at - 1)) && (after >= code.length || !isIdentUnit(code, after))
    }

    private fun identEnd(code: String, at: Int): Int {
        var end = at
        while (isIdentUnit(code, end)) end++
        return end
    }

    private fun skipString(code: String, open: Int): Int? {
        val quote = code[open]
        var i = open + 1
        while (i < code.length) {
            if (code[i] == '\\') {
                i += 2
                continue
            }
            if (code[i] == quote) return i + 1
            i++
        }
        return null
    }

    private class TemplateFrame(val content: Boolean, var braceDepth: Int = 0)

    private fun skipTemplate(code: String, open: Int): Int? {
        val stack = ArrayList<TemplateFrame>().apply { add(TemplateFrame(content = true)) }
        var i = open + 1
        while (stack.isNotEmpty()) {
            if (stack.size > MAX_TEMPLATE_FRAMES) return null
            if (i >= code.length) return null
            val c = code[i]
            val frame = stack.last()
            if (frame.content) {
                when {
                    c == '`' -> {
                        stack.removeAt(stack.lastIndex)
                        i++
                        if (stack.isEmpty()) return i
                    }
                    c == '\\' -> i += 2
                    c == '$' && code.at(i + 1) == '{' -> {
                        stack.add(TemplateFrame(content = false))
                        i += 2
                    }
                    else -> i++
                }
            } else {
                when {
                    c == '{' -> {
                        frame.braceDepth++
                        i++
                    }
                    c == '}' -> {
                        if (frame.braceDepth == 0) stack.removeAt(stack.lastIndex) else frame.braceDepth--
                        i++
                    }
                    c == '\'' || c == '"' -> i = skipString(code, i) ?: return null
                    c == '`' -> {
                        stack.add(TemplateFrame(content = true))
                        i++
                    }
                    c == '/' && code.at(i + 1) == '/' -> while (i < code.length && !isLineTerminator(code[i])) i++
                    c == '/' && code.at(i + 1) == '*' -> {
                        val close = code.indexOf("*/", i + 2)
                        if (close == -1) return null
                        i = close + 2
                    }
                    else -> i++
                }
            }
        }
        return null
    }

    // Rust's `u8::is_ascii_whitespace`: space, \t, \n, \f, \r (not \v).
    private fun isWs(c: Char?): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\u000C' || c == '\r'

    // ECMA-262 line comments end at any LineTerminator, not just LF.
    private fun isLineTerminator(c: Char?): Boolean = c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'

    private fun skipWsAndComments(code: String, from: Int): Int {
        var i = from
        while (true) {
            if (isWs(code.at(i))) {
                i++
                continue
            }
            if (code.at(i) == '/' && code.at(i + 1) == '/') {
                i += 2
                while (i < code.length && !isLineTerminator(code[i])) i++
                continue
            }
            if (code.at(i) == '/' && code.at(i + 1) == '*') {
                val close = code.indexOf("*/", i + 2)
                if (close == -1) return code.length
                i = close + 2
                continue
            }
            return i
        }
    }

    /** Whether [code] is longer than [cap] UTF-8 bytes, counted without encoding it. */
    private fun utf8LengthExceeds(code: String, cap: Int): Boolean {
        if (code.length > cap) return true
        if (code.length.toLong() * 3 <= cap) return false
        var bytes = 0
        for (ch in code) {
            val c = ch.code
            bytes += if (c < 0x80) 1 else if (c < 0x800 || c in 0xD800..0xDFFF) 2 else 3
            if (bytes > cap) return true
        }
        return false
    }

    /** Keywords that make what follows them conditional, repeated or deferred
     *  (`CODE_MODE_BRANCH_KEYWORDS` in `acp/lifecycle.rs`). */
    private val BRANCH_KEYWORDS = listOf("if", "else", "switch", "for", "while", "do", "catch", "yield", "function")

    /**
     * The first `delegate_to_agent(` call reached through a `tools` accessor: its argument
     * list's `(open, close)`, and whether those arguments may be read as the delegation's — it
     * is the only call site in a scan that reached the end, or it provably runs before any
     * other (brace depth 0, outside any parameter list or destructuring pattern, nothing before
     * it that branches, defers or hides code in a `${…}`). Null past the scan cap, on
     * unbalanced input before the call, with no such call, or when a `/` that is no comment
     * precedes it (a regex literal can't be told from division here). Web `codeModeCallSite`.
     */
    private fun callSite(code: String): Site? {
        if (utf8LengthExceeds(code, MAX_CODE_BYTES)) return null
        var i = 0
        var depth = 0
        // The `(` / `[` groups open at `i`, innermost last.
        val groups = ArrayList<Char>()
        var branched = false
        var firstOpen = -1
        var firstClose = -1
        var runsFirst = false
        // How many groups enclosing the first site have yet to close; each must close as a
        // call, a grouping or an array — not as an arrow's parameters or a pattern.
        var enclosing = 0
        var sites = 0
        var counted = true
        scan@ while (i < code.length) {
            val c = code[i]
            when {
                c == '\'' || c == '"' || c == '`' -> {
                    val end = if (c == '`') skipTemplate(code, i) else skipString(code, i)
                    if (end == null) {
                        if (firstOpen < 0) return null
                        counted = false
                        break@scan
                    }
                    // A `${…}` runs code the scan skips: a call in it is one the count can't
                    // see, and before the first site it may run first.
                    if (c == '`' && code.substring(i, end).contains("\${")) {
                        counted = false
                        if (firstOpen < 0) branched = true
                    }
                    i = end
                }
                c == '/' && (code.at(i + 1) == '/' || code.at(i + 1) == '*') -> i = skipWsAndComments(code, i)
                c == '/' -> {
                    if (firstOpen < 0) return null
                    counted = false
                    break@scan
                }
                c == '{' -> {
                    depth++
                    i++
                }
                c == '}' -> {
                    depth--
                    i++
                }
                c == '(' || c == '[' -> {
                    groups.add(c)
                    i++
                }
                c == ')' || c == ']' -> {
                    if (groups.isNotEmpty()) groups.removeAt(groups.lastIndex)
                    i++
                    if (enclosing > groups.size) {
                        enclosing = groups.size
                        val next = skipWsAndComments(code, i)
                        val parameters = c == ')' && code.at(next) == '=' && code.at(next + 1) == '>'
                        val pattern = c == ']' && code.at(next) == '=' && code.at(next + 1) != '=' && code.at(next + 1) != '>'
                        if (parameters || pattern) runsFirst = false
                    }
                }
                c == '?' -> {
                    branched = true
                    i++
                }
                (c == '&' || c == '|') && code.at(i + 1) == c -> {
                    branched = true
                    i += 2
                }
                c == '=' && code.at(i + 1) == '>' -> {
                    branched = true
                    i += 2
                }
                else -> {
                    for (keyword in BRANCH_KEYWORDS) {
                        if (isWordAt(code, i, keyword)) {
                            branched = true
                            i++
                            continue@scan
                        }
                    }
                    if (isWordAt(code, i, "tools")) {
                        val open = toolsCallOpen(code, i)
                        if (open != null) {
                            val close = matchingClose(code, open, '(', ')')
                            if (close == null) {
                                if (firstOpen < 0) return null
                                counted = false
                                break@scan
                            }
                            sites++
                            if (firstOpen < 0) {
                                firstOpen = open
                                firstClose = close
                                runsFirst = depth == 0 && !branched
                                enclosing = groups.size
                            }
                            i = close + 1
                            continue@scan
                        }
                    }
                    i++
                }
            }
        }
        if (firstOpen < 0) return null
        // A group around the first site that never closed could still be parameters or a pattern.
        val provable = runsFirst && enclosing == 0
        return Site(firstOpen, firstClose, provable || (sites == 1 && counted))
    }

    /** The `(` of a `tools.<ns>.delegate_to_agent(` / `tools["<ns>"].delegate_to_agent(` call. */
    private fun toolsCallOpen(code: String, toolsAt: Int): Int? {
        var i = skipWsAndComments(code, toolsAt + "tools".length)
        when (code.at(i)) {
            '[' -> {
                i = skipWsAndComments(code, i + 1)
                val quote = code.at(i)
                if (quote != '\'' && quote != '"') return null
                val end = skipString(code, i) ?: return null
                if (end == i + 2) return null
                i = skipWsAndComments(code, end)
                if (code.at(i) != ']') return null
                i = skipWsAndComments(code, i + 1)
                if (code.at(i) != '.') return null
                i = skipWsAndComments(code, i + 1)
            }
            '.' -> {
                i = skipWsAndComments(code, i + 1)
                val end = identEnd(code, i)
                if (end == i) return null
                i = skipWsAndComments(code, end)
                if (code.at(i) != '.') return null
                i = skipWsAndComments(code, i + 1)
            }
            else -> return null
        }
        if (!isWordAt(code, i, "delegate_to_agent")) return null
        i = skipWsAndComments(code, i + "delegate_to_agent".length)
        return if (code.at(i) == '(') i else null
    }

    /** Offset of the [closeCh] matching the [openCh] at [open], skipping strings, templates and
     *  comments (`matching_close` in `acp/lifecycle.rs`). */
    private fun matchingClose(code: String, open: Int, openCh: Char, closeCh: Char): Int? {
        var depth = 0
        var i = open
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\'' || c == '"' -> i = skipString(code, i) ?: return null
                c == '`' -> i = skipTemplate(code, i) ?: return null
                c == '/' && (code.at(i + 1) == '/' || code.at(i + 1) == '*') -> i = skipWsAndComments(code, i)
                c == openCh -> {
                    depth++
                    i++
                }
                c == closeCh -> {
                    depth--
                    if (depth == 0) return i
                    i++
                }
                else -> i++
            }
        }
        return null
    }

    /** Whether the argument list `(open, close)` holds exactly ONE object literal, a trailing
     *  comma aside (`code_mode_single_object_argument`). */
    private fun singleObjectArgument(code: String, open: Int, close: Int): Boolean {
        val start = skipWsAndComments(code, open + 1)
        if (code.at(start) != '{') return false
        val objectClose = matchingClose(code, start, '{', '}') ?: return false
        var after = skipWsAndComments(code, objectClose + 1)
        if (code.at(after) == ',') after = skipWsAndComments(code, after + 1)
        return after == close
    }

    /**
     * Unit offset of the VALUE of a top-level `key:` inside `[start, end)`, both spellings of
     * the key (bare, and escape-free quoted), at brace depth 1 only — the backend reads FLAT
     * args. Scanning stops where the argument object closes; duplicates take the LAST
     * occurrence, as the runtime does (`code_mode_find_key`).
     */
    private fun findKey(code: String, start: Int, end: Int, key: String): Int? {
        var i = start
        var depth = 0
        var found: Int? = null
        while (i < end) {
            val c = code[i]
            when {
                c == '\'' || c == '"' -> {
                    val s = skipString(code, i) ?: return null
                    if (depth == 1 && s >= i + 2) {
                        val inner = code.substring(i + 1, s - 1)
                        val after = skipWsAndComments(code, s)
                        if (code.at(after) == ':' && !inner.contains('\\') && inner == key) {
                            found = skipWsAndComments(code, after + 1)
                        }
                    }
                    i = s
                }
                c == '`' -> i = skipTemplate(code, i) ?: return null
                c == '/' && (code.at(i + 1) == '/' || code.at(i + 1) == '*') -> i = skipWsAndComments(code, i)
                c == '{' -> {
                    depth++
                    i++
                }
                c == '}' -> {
                    // Closes the argument object: nothing after it is among the flat args.
                    if (depth <= 1) break
                    depth--
                    i++
                }
                depth == 1 && isWordAt(code, i, key) -> {
                    val after = skipWsAndComments(code, i + key.length)
                    if (code.at(after) == ':') found = skipWsAndComments(code, after + 1)
                    i++
                }
                else -> i++
            }
        }
        return found
    }

    /** Whether the value ending at [end] ends its property: a literal followed by an operator
     *  evaluates to something else at runtime. */
    private fun valueEndsObjectProperty(code: String, end: Int): Boolean {
        val after = skipWsAndComments(code, end)
        return code.at(after) == ',' || code.at(after) == '}'
    }

    /** The escape-free `'…'` / `"…"` string at [at] and the offset past it; null with a backslash. */
    private fun plainStringValueEnd(code: String, at: Int): Pair<String, Int>? {
        val quote = code.at(at)
        if (quote != '\'' && quote != '"') return null
        val end = skipString(code, at) ?: return null
        val inner = code.substring(at + 1, end - 1)
        if (inner.contains('\\')) return null
        return inner to end
    }

    /** An `agent_type` / `working_dir` value: an escape-free string ending its property. */
    private fun objectStringValue(code: String, at: Int): String? {
        val r = plainStringValueEnd(code, at) ?: return null
        return if (valueEndsObjectProperty(code, r.second)) r.first else null
    }

    /** A `task` value: a template literal as the runtime cooks it, or an escape-free string. */
    private fun objectTaskValue(code: String, at: Int): String? {
        val r = when (code.at(at)) {
            '`' -> templateLiteralTextEnd(code, at)
            '\'', '"' -> plainStringValueEnd(code, at)
            else -> null
        } ?: return null
        return if (valueEndsObjectProperty(code, r.second)) r.first else null
    }

    /** An `assistant_id` value: an escape-free quoted number, or bare digits. */
    private fun readAssistantIdValue(code: String, at: Int): String? {
        plainStringValueEnd(code, at)?.let { quoted ->
            return if (valueEndsObjectProperty(code, quoted.second)) quoted.first else null
        }
        var i = at
        while (i < code.length && code[i] in '0'..'9') i++
        if (i == at) return null
        if (!valueEndsObjectProperty(code, i)) return null
        return code.substring(at, i)
    }

    private fun hexDigitValue(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    // `\u` escapes denote scalar values: lone surrogates and anything past U+10FFFF are invalid.
    private fun unicodeFromU32(v: Long): String? {
        if (v > 0x10FFFF) return null
        if (v in 0xD800..0xDFFF) return null
        return String(Character.toChars(v.toInt()))
    }

    private fun readHexDigits(code: String, at: Int, count: Int): Pair<Long, Int>? {
        if (at + count > code.length) return null
        var v = 0L
        for (k in 0 until count) {
            val d = hexDigitValue(code[at + k]) ?: return null
            v = v * 16 + d
        }
        return v to at + count
    }

    /**
     * The cooked text of the template literal at [at] and the offset past its closing backtick
     * — the exact string the runtime passes on: the minimal escape set decodes, a line
     * continuation contributes nothing, a bare CR or CRLF becomes one LF (U+2028/U+2029 pass
     * through, as ECMA-262 normalizes only those two). Null for `${…}`, any other escape, and
     * legacy octal.
     */
    private fun templateLiteralTextEnd(code: String, at: Int): Pair<String, Int>? {
        val out = StringBuilder()
        var i = at + 1
        while (i < code.length) {
            val c = code[i]
            if (c == '`') return out.toString() to i + 1
            if (c == '\\') {
                i++
                val e = code.at(i) ?: return null
                i++
                when (e) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'v' -> out.append('\u000B')
                    '0' -> {
                        // Legacy octal (`\01` ≠ `\0` + `1`) is not supported.
                        if (code.at(i)?.let { it in '0'..'9' } == true) return null
                        out.append('\u0000')
                    }
                    '\\' -> out.append('\\')
                    '`' -> out.append('`')
                    '$' -> out.append('$')
                    '\'' -> out.append('\'')
                    '"' -> out.append('"')
                    'u' -> if (code.at(i) == '{') {
                        val close = code.indexOf('}', i)
                        if (close == -1) return null
                        val digits = code.substring(i + 1, close)
                        if (digits.isEmpty() || digits.length > 6) return null
                        var v = 0L
                        for (d in digits) v = v * 16 + (hexDigitValue(d) ?: return null)
                        out.append(unicodeFromU32(v) ?: return null)
                        i = close + 1
                    } else {
                        val r = readHexDigits(code, i, 4) ?: return null
                        out.append(unicodeFromU32(r.first) ?: return null)
                        i = r.second
                    }
                    'x' -> {
                        val r = readHexDigits(code, i, 2) ?: return null
                        out.append(unicodeFromU32(r.first) ?: return null)
                        i = r.second
                    }
                    // Line continuations: the newline is dropped.
                    '\r' -> if (code.at(i) == '\n') i++
                    '\n' -> Unit
                    else -> return null
                }
            } else if (c == '$' && code.at(i + 1) == '{') {
                return null
            } else if (c == '\r') {
                // A bare CR (or a CRLF's) is TV-normalized to one LF.
                out.append('\n')
                i++
                if (code.at(i) == '\n') i++
            } else {
                out.append(c)
                i++
            }
        }
        return null
    }

    /** The argument keys the scanner reads: a bare one not followed by `:` is a shorthand
     *  property, whose value is a runtime value. */
    private val ARG_KEYS = listOf("agent_type", "task", "working_dir", "assistant_id")

    /**
     * Conservative pre-pass over the argument region (`code_mode_args_transparent`): false on
     * syntax whose effect the scanner can't evaluate — spread, a `[` (computed keys, arrays), a
     * quoted key with a backslash, a call / grouping / division / regex, a `?`, a `\` outside a
     * string (a Unicode-escaped identifier), a non-ASCII unit outside a string, a shorthand.
     */
    private fun argsTransparent(code: String, start: Int, end: Int): Boolean {
        var i = start
        scan@ while (i < end) {
            val c = code[i]
            when {
                c == '\'' || c == '"' -> {
                    val s = skipString(code, i) ?: return false
                    val after = skipWsAndComments(code, s)
                    if (code.at(after) == ':' && code.substring(i + 1, s - 1).contains('\\')) return false
                    i = s
                }
                c == '`' -> i = skipTemplate(code, i) ?: return false
                c == '/' && (code.at(i + 1) == '/' || code.at(i + 1) == '*') -> i = skipWsAndComments(code, i)
                c == '/' || c == '(' || c == ')' -> return false
                c == '[' || c == '?' || c == '\\' -> return false
                c.code >= 0x80 -> return false
                c == '.' && code.at(i + 1) == '.' && code.at(i + 2) == '.' -> return false
                else -> {
                    for (key in ARG_KEYS) {
                        if (!isWordAt(code, i, key)) continue
                        val after = skipWsAndComments(code, i + key.length)
                        if (code.at(after) != ':') return false
                        i += key.length
                        continue@scan
                    }
                    i++
                }
            }
        }
        return true
    }

    // endregion

    // JS `\s` / `trim`: ASCII whitespace, NBSP, the Unicode spaces, LS/PS and the BOM.
    private const val JS_WHITESPACE = "\t\n\u000B\u000C\r \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005" +
        "\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"

    /** JavaScript's `String.prototype.trim`. */
    fun jsTrim(s: String): String = s.trim { it in JS_WHITESPACE }
}
