package app.codeg.android.feature.sessiondetail.rendering

import kotlin.math.ceil

/**
 * Parse the stdout of a search tool (ripgrep / grep / Glob / codex's `search`
 * and `listFiles` command actions) into a structure the card can show as
 * grouped results instead of a wall of text.
 *
 * Two recognised shapes:
 *  - [Mode.MATCHES] — `<path>:<line>:<content>`, grep/rg with line numbers
 *  - [Mode.FILES]   — one bare path per line, `rg --files` / Glob / `ls`
 *
 * Recognition is deliberately conservative: unless nearly every line fits one
 * shape the parser returns null and the caller falls back to plain text. Search
 * output is agent- and flag-dependent (`rg -c` counts, `--heading` groups, ANSI
 * colours, "Found N files" preambles), and a half-parsed result reads far worse
 * than the raw text.
 *
 * Port of the web `search-output.ts`.
 */
object SearchOutput {

    enum class Mode { MATCHES, FILES }

    /** 1-based line number in the owning file; null in [Mode.FILES]. */
    data class MatchLine(val line: Int?, val text: String)

    /** [lines] is empty in [Mode.FILES]. */
    data class FileGroup(val path: String, val lines: List<MatchLine>)

    data class Parsed(
        val mode: Mode,
        val groups: List<FileGroup>,
        /** Total matched lines across all files (0 in [Mode.FILES]). */
        val matchCount: Int,
        val fileCount: Int,
        /** Lines dropped for display — rows past `maxRows`, notes past [MAX_NOTES]. */
        val hiddenCount: Int,
        /** Lines fitting neither shape (rg warnings, "Found N files" preambles, …). */
        val notes: List<String>,
    )

    /** `<path>:<line>:<content>`, path lazy so a Windows drive letter still parses. */
    private val MATCH_LINE = Regex("""^(.*?):(\d+):([\s\S]*)$""")

    /** ripgrep's context separator between non-adjacent hunks. */
    private val CONTEXT_SEPARATOR = Regex("""^--+$""")

    /** Trailing extension, used to accept extension-only names from `ls`. */
    private val FILE_EXTENSION = Regex("""\.[A-Za-z0-9_+-]+$""")

    private val NUMERIC_ONLY = Regex("""^[\d.]+$""")
    private val PATH_CHAR = Regex("""[./\\]""")
    private val SLASH = Regex("""[/\\]""")

    /** Minimum share of considered lines that must fit a shape to accept it. */
    private const val MIN_PARSE_RATIO = 0.8
    private const val DEFAULT_MAX_ROWS = 200

    /**
     * Notes kept for display. The ratio gate lets a fifth of the output be
     * notes, so a search over a tree with unreadable directories can pair
     * 40 000 matches with 10 000 `rg: …: Permission denied` lines and still
     * parse — and the card renders them in a plain [androidx.compose.foundation.layout.Column]
     * inside one transcript item, where nothing virtualises.
     *
     * The web maps all of them; a browser survives that and a phone need not.
     * The remainder is added to `hiddenCount` rather than dropped silently.
     */
    private const val MAX_NOTES = 20

    /**
     * A path in `<path>:<line>:<content>` must look like a filesystem path, not
     * a number — otherwise timestamps (`12:34:56 done`) and `line:col`
     * diagnostics parse as matches. Spaces are allowed: the `:<digits>:` anchor
     * is specific enough, and paths with spaces are real.
     */
    private fun looksLikeMatchPath(value: String): Boolean {
        if (value.isEmpty()) return false
        if (!PATH_CHAR.containsMatchIn(value)) return false
        return !NUMERIC_ONLY.matches(value)
    }

    /**
     * A bare path line. Stricter than [looksLikeMatchPath] — with no `:<line>:`
     * anchor, whitespace is the only thing separating a path from prose like
     * "(Results are truncated, consider using a more specific path.)". The two
     * exclusions cover the shapes that otherwise sneak past the extension rule:
     * version-like tokens (`rg -o '\d+\.\d+\.\d+'` → `1.2.3`) and JSON records
     * (`rg --json`), neither of which is a file.
     */
    private fun looksLikeBarePath(value: String): Boolean {
        if (value.isEmpty() || value.any { it.isWhitespace() }) return false
        if (NUMERIC_ONLY.matches(value)) return false
        if (value.startsWith("{") || value.startsWith("[")) return false
        return SLASH.containsMatchIn(value) || FILE_EXTENSION.containsMatchIn(value)
    }

    fun parse(text: String, maxRows: Int = DEFAULT_MAX_ROWS): Parsed? {
        if (text.isBlank()) return null

        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val matchGroups = LinkedHashMap<String, MutableList<MatchLine>>()
        val filePaths = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var considered = 0
        var matchLineCount = 0

        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            if (CONTEXT_SEPARATOR.matches(line.trim())) continue
            considered += 1

            val match = MATCH_LINE.find(line)
            if (match != null && looksLikeMatchPath(match.groupValues[1])) {
                val path = match.groupValues[1]
                val number = match.groupValues[2].toIntOrNull()
                if (number != null) {
                    matchGroups.getOrPut(path) { mutableListOf() }
                        .add(MatchLine(number, match.groupValues[3]))
                    matchLineCount += 1
                    continue
                }
            }

            val trimmed = line.trim()
            if (looksLikeBarePath(trimmed)) {
                filePaths.add(trimmed)
                continue
            }

            notes.add(trimmed)
        }

        if (considered == 0) return null
        val threshold = ceil(considered * MIN_PARSE_RATIO).toInt()

        if (matchLineCount > 0 && matchLineCount >= threshold) {
            val groups = matchGroups.map { (path, entries) -> FileGroup(path, entries) }
            return capRows(
                Parsed(Mode.MATCHES, groups, matchLineCount, groups.size, 0, notes),
                maxRows,
            )
        }

        if (filePaths.isNotEmpty() && filePaths.size >= threshold) {
            return capRows(
                Parsed(
                    mode = Mode.FILES,
                    groups = filePaths.map { FileGroup(it, emptyList()) },
                    matchCount = 0,
                    fileCount = filePaths.size,
                    hiddenCount = 0,
                    notes = notes,
                ),
                maxRows,
            )
        }

        return null
    }

    /**
     * Trim the result set to [maxRows] renderable rows — matched lines in
     * [Mode.MATCHES], files in [Mode.FILES] — recording the remainder in
     * `hiddenCount`. The counts stay authoritative: they describe the full
     * output, not the shown part.
     */
    private fun capRows(parsed: Parsed, maxRows: Int): Parsed = capNotes(capGroups(parsed, maxRows))

    private fun capGroups(parsed: Parsed, maxRows: Int): Parsed {
        if (maxRows <= 0) return parsed

        if (parsed.mode == Mode.FILES) {
            if (parsed.groups.size <= maxRows) return parsed
            return parsed.copy(
                groups = parsed.groups.take(maxRows),
                hiddenCount = parsed.groups.size - maxRows,
            )
        }

        if (parsed.matchCount <= maxRows) return parsed
        val groups = mutableListOf<FileGroup>()
        var shown = 0
        for (group in parsed.groups) {
            if (shown >= maxRows) break
            val lines = group.lines.take(maxRows - shown)
            shown += lines.size
            groups.add(FileGroup(group.path, lines))
        }
        return parsed.copy(groups = groups, hiddenCount = parsed.matchCount - shown)
    }

    private fun capNotes(parsed: Parsed): Parsed {
        if (parsed.notes.size <= MAX_NOTES) return parsed
        return parsed.copy(
            notes = parsed.notes.take(MAX_NOTES),
            hiddenCount = parsed.hiddenCount + (parsed.notes.size - MAX_NOTES),
        )
    }
}
