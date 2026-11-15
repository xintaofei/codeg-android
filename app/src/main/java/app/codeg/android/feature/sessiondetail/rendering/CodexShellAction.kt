package app.codeg.android.feature.sessiondetail.rendering

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A codex shell command read the way codex reads it (codex `parse_command`), for the copy of
 * the call its transcript keeps.
 *
 * Live, codex-acp announces a command that codex takes for a file read, a listing or a search
 * as that action ([CodexCommandAction]): "Read file '…'", "List files in '…'". The transcript
 * keeps only `exec_command` and the command line, so the same reply read back from it showed
 * its steps as commands — "Ran 2 commands" where it had streamed as "Read 2 files". This
 * gives the saved call the identity the live one had.
 *
 * Only for a single plain command, by codex's own rules for it: `cat`, `head`/`tail`, `sed -n`,
 * `nl`, `bat`, `less`, `more` read; `ls`, `eza`, `tree`, `du`, `rg --files`, `git ls-files`
 * list; `rg`, `grep`, `git grep` search. Anything with a pipe, a connector, a redirect, a
 * substitution or a glob stays a command here, including some codex does read (`cd x && cat y`,
 * `cat y | head`) — those keep the mismatch rather than guess at more of codex's grammar.
 */
object CodexShellAction {

    /** The tool identity the live call had, and the input that names its target. */
    fun resolve(command: String): CodexCommandAction.Resolved? {
        val tokens = split(command) ?: return null
        val (head, tail) = tokens.firstOrNull()?.let { it to tokens.drop(1) } ?: return null
        return when (head) {
            "ls" -> listFiles(firstOperand(tail, LS_FLAGS_WITH_VALUES))
            "eza", "exa" -> listFiles(firstOperand(tail, EZA_FLAGS_WITH_VALUES))
            "tree" -> listFiles(firstOperand(tail, TREE_FLAGS_WITH_VALUES))
            "du" -> listFiles(firstOperand(tail, DU_FLAGS_WITH_VALUES))
            "rg", "rga", "ripgrep-all" -> ripgrep(tail)
            "git" -> when (tail.firstOrNull()) {
                "grep" -> grepLike(tail.drop(1))
                "ls-files" -> listFiles(firstOperand(tail.drop(1), GIT_LS_FILES_FLAGS_WITH_VALUES))
                else -> null
            }
            "grep", "egrep", "fgrep" -> grepLike(tail)
            "cat", "more" -> singleOperand(tail, emptyList())?.let(::read)
            "bat", "batcat" -> singleOperand(tail, BAT_FLAGS_WITH_VALUES)?.let(::read)
            "less" -> singleOperand(tail, LESS_FLAGS_WITH_VALUES)?.let(::read)
            "head" -> headTailPath(tail, allowPlus = false)?.let(::read)
            "tail" -> headTailPath(tail, allowPlus = true)?.let(::read)
            "nl" -> skipFlagValues(tail, NL_FLAGS_WITH_VALUES).firstOrNull { !it.startsWith("-") }?.let(::read)
            "sed" -> sedReadPath(tail)?.let(::read)
            else -> null
        }
    }

    /** The command line a saved `exec_command` ran: its text, or the `cmd` of a JSON input. */
    fun commandOf(input: String?, parsed: JsonObject?): String? {
        val args = ToolDerive.effectiveArgs(parsed) ?: return input?.takeIf { it.isNotBlank() }
        for (key in listOf("cmd", "command")) {
            val value = args[key] as? JsonPrimitive
            if (value != null && value.isString) return value.content
        }
        return null
    }

    private fun read(path: String) = CodexCommandAction.Resolved("read", buildJsonObject { put("file_path", path) })

    private fun listFiles(path: String?) =
        CodexCommandAction.Resolved("glob", buildJsonObject { path?.let { put("path", shortDisplayPath(it)) } })

    private fun search(query: String?, path: String?) = CodexCommandAction.Resolved(
        "grep",
        buildJsonObject {
            query?.let { put("pattern", it) }
            path?.let { put("path", shortDisplayPath(it)) }
        },
    )

    private fun ripgrep(args: List<String>): CodexCommandAction.Resolved {
        val nonFlags = skipFlagValues(args, RG_FLAGS_WITH_VALUES).filter { !it.startsWith("-") }
        return if ("--files" in args) {
            listFiles(nonFlags.firstOrNull())
        } else {
            search(nonFlags.firstOrNull(), nonFlags.getOrNull(1))
        }
    }

    private fun grepLike(args: List<String>): CodexCommandAction.Resolved {
        val operands = mutableListOf<String>()
        var pattern: String? = null
        var afterDoubleDash = false
        var i = 0
        while (i < args.size) {
            val arg = args[i++]
            if (afterDoubleDash) {
                operands += arg
                continue
            }
            when (arg) {
                "--" -> afterDoubleDash = true
                "-e", "--regexp", "-f", "--file" -> {
                    val value = args.getOrNull(i++)
                    if (pattern == null) pattern = value
                }
                "-m", "--max-count", "-C", "--context", "-A", "--after-context", "-B", "--before-context" -> i++
                else -> if (!arg.startsWith("-")) operands += arg
            }
        }
        val query = pattern ?: operands.firstOrNull()
        val path = operands.getOrNull(if (pattern != null) 0 else 1)
        return search(query, path)
    }

    /** `head -n 50 f`, `head -n50 f` (`tail` also `-n +10`), or one bare file; not `head -5 f`. */
    private fun headTailPath(args: List<String>, allowPlus: Boolean): String? {
        fun count(value: String): Boolean {
            val digits = if (allowPlus) value.removePrefix("+") else value
            return digits.isNotEmpty() && digits.all(::isAsciiDigit)
        }
        val first = args.firstOrNull()
        val validN = when {
            first == null -> false
            first == "-n" -> args.getOrNull(1)?.let(::count) == true
            first.startsWith("-n") -> count(first.substring(2))
            else -> false
        }
        if (validN) {
            val candidates = if (first == "-n") args.drop(2) else args.drop(1)
            candidates.firstOrNull { !it.startsWith("-") }?.let { return it }
        }
        return args.singleOrNull()?.takeIf { !it.startsWith("-") }
    }

    private fun sedReadPath(args: List<String>): String? {
        if (sedEditsInPlace(args) || "-n" !in args) return null
        var hasRange = false
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-e", "--expression" -> {
                    if (isSedRange(args.getOrNull(i + 1))) hasRange = true
                    i += 2
                }
                "-f", "--file" -> i += 2
                else -> i++
            }
        }
        if (!hasRange) hasRange = args.any { !it.startsWith("-") && isSedRange(it) }
        if (!hasRange) return null
        val nonFlags = skipFlagValues(args, SED_FLAGS_WITH_VALUES).filter { !it.startsWith("-") }
        val first = nonFlags.firstOrNull() ?: return null
        return if (isSedRange(first)) nonFlags.getOrNull(1) else first
    }

    /** `Np` or `N,Mp`. */
    private fun isSedRange(arg: String?): Boolean {
        val core = arg?.removeSuffix("p")?.takeIf { it != arg } ?: return false
        val parts = core.split(",")
        return parts.size in 1..2 && parts.all { part -> part.isNotEmpty() && part.all(::isAsciiDigit) }
    }

    private fun sedEditsInPlace(args: List<String>): Boolean {
        var i = 0
        while (i < args.size) {
            val token = args[i++]
            when {
                token == "--" -> return false
                token in setOf("-e", "-f", "--expression", "--file") -> i++
                token == "--in-place" || token.startsWith("--in-place=") -> return true
                token.startsWith("--") -> Unit
                token.startsWith("-") -> {
                    val options = token.substring(1)
                    for ((index, option) in options.withIndex()) {
                        if (option == 'i') return true
                        if (option == 'e' || option == 'f') {
                            if (index == options.lastIndex) i++
                            break
                        }
                    }
                }
            }
        }
        return false
    }

    private fun skipFlagValues(args: List<String>, flagsWithValues: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--" -> {
                    out += args.drop(i + 1)
                    return out
                }
                arg.startsWith("--") && '=' in arg -> Unit
                arg in flagsWithValues -> if (i + 1 < args.size) i++
                else -> out += arg
            }
            i++
        }
        return out
    }

    private fun operands(args: List<String>, flagsWithValues: List<String>): List<String> {
        val out = mutableListOf<String>()
        var afterDoubleDash = false
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                afterDoubleDash -> out += arg
                arg == "--" -> afterDoubleDash = true
                arg.startsWith("--") && '=' in arg -> Unit
                arg in flagsWithValues -> if (i + 1 < args.size) i++
                arg.startsWith("-") -> Unit
                else -> out += arg
            }
            i++
        }
        return out
    }

    private fun firstOperand(args: List<String>, flagsWithValues: List<String>) = operands(args, flagsWithValues).firstOrNull()

    private fun singleOperand(args: List<String>, flagsWithValues: List<String>) = operands(args, flagsWithValues).singleOrNull()

    private fun isAsciiDigit(c: Char) = c in '0'..'9'

    /** The path's last telling component, as codex shows it: `webview/src` → `webview`. */
    internal fun shortDisplayPath(path: String): String {
        val trimmed = path.replace('\\', '/').trimEnd('/')
        return trimmed.split('/').asReversed().firstOrNull { it.isNotEmpty() && it !in UNTELLING_DIRS } ?: trimmed
    }

    /**
     * Shell words, or null for a line that is more than one plain command: an unquoted
     * operator, redirect, substitution, glob or line break, or an unclosed quote.
     */
    internal fun split(line: String): List<String>? {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\'' -> {
                    val end = line.indexOf('\'', i + 1).takeIf { it >= 0 } ?: return null
                    word.append(line, i + 1, end)
                    inWord = true
                    i = end
                }
                c == '"' -> {
                    var j = i + 1
                    while (true) {
                        if (j >= line.length) return null
                        val d = line[j]
                        if (d == '"') break
                        if (d == '$' || d == '`') return null
                        if (d == '\\' && j + 1 < line.length && line[j + 1] in "\"\\$`") {
                            word.append(line[j + 1])
                            j += 2
                            continue
                        }
                        word.append(d)
                        j++
                    }
                    inWord = true
                    i = j
                }
                c == '\\' -> {
                    if (i + 1 >= line.length || line[i + 1] == '\n') return null
                    word.append(line[i + 1])
                    inWord = true
                    i++
                }
                c == ' ' || c == '\t' -> if (inWord) {
                    words += word.toString()
                    word.clear()
                    inWord = false
                }
                c in SHELL_SPECIAL -> return null
                else -> {
                    word.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (inWord) words += word.toString()
        return words.takeIf { it.isNotEmpty() }
    }

    private const val SHELL_SPECIAL = "|&;<>()\$`*?[]{}~#!\n\r"
    private val UNTELLING_DIRS = setOf("build", "dist", "node_modules", "src")
    private val LS_FLAGS_WITH_VALUES = listOf("-I", "-w", "--block-size", "--format", "--time-style", "--color", "--quoting-style")
    private val EZA_FLAGS_WITH_VALUES = listOf("-I", "--ignore-glob", "--color", "--sort", "--time-style", "--time")
    private val TREE_FLAGS_WITH_VALUES = listOf("-L", "-P", "-I", "--charset", "--filelimit", "--sort")
    private val DU_FLAGS_WITH_VALUES = listOf("-d", "--max-depth", "-B", "--block-size", "--exclude", "--time-style")
    private val RG_FLAGS_WITH_VALUES = listOf(
        "-g", "--glob", "--iglob", "-t", "--type", "--type-add", "--type-not", "-m", "--max-count",
        "-A", "-B", "-C", "--context", "--max-depth",
    )
    private val GIT_LS_FILES_FLAGS_WITH_VALUES = listOf("--exclude", "--exclude-from", "--pathspec-from-file")
    private val BAT_FLAGS_WITH_VALUES = listOf(
        "--theme", "--language", "--style", "--terminal-width", "--tabs", "--line-range", "--map-syntax",
    )
    private val LESS_FLAGS_WITH_VALUES = listOf(
        "-p", "-P", "-x", "-y", "-z", "-j", "--pattern", "--prompt", "--tabs", "--shift", "--jump-target",
    )
    private val NL_FLAGS_WITH_VALUES = listOf("-s", "-w", "-v", "-i", "-b")
    private val SED_FLAGS_WITH_VALUES = listOf("-e", "-f", "--expression", "--file")
}
