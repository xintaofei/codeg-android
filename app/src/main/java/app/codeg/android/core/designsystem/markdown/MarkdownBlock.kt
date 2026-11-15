package app.codeg.android.core.designsystem.markdown

/**
 * A parsed block of Markdown. Inline spans are parsed at render time (per the
 * current theme colors); these blocks hold the raw inline source. Faithful port
 * of the iOS `MarkdownBlock` / `MarkdownContent.parseBlocks`.
 */
sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class BulletList(val items: List<String>) : MarkdownBlock
    data class NumberedList(val items: List<Pair<String, String>>) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    /**
     * A fenced block. [closed]: its closing fence was found — a fence the text ends inside
     * (still streaming in, or cut off) is not. [closingLineEnded]: that closing line is
     * itself finished, by a newline after it; while the text streams, a closing ``` that
     * is still the last thing received may yet turn into ```` or ```js (see
     * [MarkdownFence.isSettled]).
     */
    data class Code(
        val language: String?,
        val code: String,
        val closed: Boolean = true,
        val closingLineEnded: Boolean = true,
    ) : MarkdownBlock
    data object Rule : MarkdownBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock
}

/**
 * Split Markdown source into real blocks — paragraphs, headings, lists, quotes,
 * fenced code, rules, GFM tables. Ported 1:1 from the iOS
 * `MarkdownContent.parseBlocks`.
 */
fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
    val lines = raw.split("\n")
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // Fenced code block.
        val fence = fenceMarker(trimmed)
        if (fence != null) {
            val lang = trimmed.drop(fence.length).trim()
            val bodyLines = mutableListOf<String>()
            var closed = false
            i += 1
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t.startsWith(fence) && t.all { it == fence[0] }) {
                    closed = true
                    i += 1; break
                }
                bodyLines.add(lines[i]); i += 1
            }
            blocks.add(
                MarkdownBlock.Code(
                    language = lang.ifEmpty { null },
                    code = bodyLines.joinToString("\n"),
                    closed = closed,
                    // A line after the closing one (even an empty one: the text ended in a
                    // newline) means the closing line is complete.
                    closingLineEnded = closed && i < lines.size,
                ),
            )
            continue
        }

        if (trimmed.isEmpty()) { i += 1; continue }

        val head = heading(trimmed)
        if (head != null) {
            blocks.add(MarkdownBlock.Heading(head.first, head.second)); i += 1; continue
        }

        if (isRule(trimmed)) {
            blocks.add(MarkdownBlock.Rule); i += 1; continue
        }

        // Blockquote (consecutive `>` lines).
        if (trimmed.startsWith(">")) {
            val quoted = mutableListOf<String>()
            while (i < lines.size) {
                val t = lines[i].trim()
                if (!t.startsWith(">")) break
                quoted.add(t.drop(1).trim())
                i += 1
            }
            blocks.add(MarkdownBlock.Quote(quoted.joinToString("\n")))
            continue
        }

        // GFM table (header row + a `|---|` separator).
        if (i + 1 < lines.size && line.contains("|") && isTableSeparator(lines[i + 1])) {
            val header = tableCells(line)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].contains("|") && lines[i].trim().isNotEmpty()) {
                rows.add(tableCells(lines[i])); i += 1
            }
            blocks.add(MarkdownBlock.Table(header, rows))
            continue
        }

        // Bulleted list.
        if (bulletContent(trimmed) != null) {
            val items = mutableListOf<String>()
            while (i < lines.size) {
                val c = bulletContent(lines[i].trim()) ?: break
                items.add(c); i += 1
            }
            blocks.add(MarkdownBlock.BulletList(items))
            continue
        }

        // Numbered list.
        if (orderedContent(trimmed) != null) {
            val items = mutableListOf<Pair<String, String>>()
            while (i < lines.size) {
                val mc = orderedContent(lines[i].trim()) ?: break
                items.add(mc); i += 1
            }
            blocks.add(MarkdownBlock.NumberedList(items))
            continue
        }

        // Paragraph: gather until a blank line or the start of another block.
        val para = mutableListOf<String>()
        while (i < lines.size) {
            val l = lines[i]
            val t = l.trim()
            if (t.isEmpty()) break
            if (fenceMarker(t) != null) break
            if (heading(t) != null) break
            if (isRule(t)) break
            if (t.startsWith(">")) break
            if (bulletContent(t) != null) break
            if (orderedContent(t) != null) break
            if (i + 1 < lines.size && l.contains("|") && isTableSeparator(lines[i + 1])) break
            para.add(l); i += 1
        }
        if (para.isNotEmpty()) blocks.add(MarkdownBlock.Paragraph(para.joinToString("\n")))
    }
    return blocks
}

private fun fenceMarker(trimmed: String): String? = when {
    trimmed.startsWith("```") -> "```"
    trimmed.startsWith("~~~") -> "~~~"
    else -> null
}

private fun heading(trimmed: String): Pair<Int, String>? {
    var n = 0
    for (ch in trimmed) if (ch == '#') n += 1 else break
    if (n !in 1..6) return null
    val rest = trimmed.drop(n)
    if (rest.isNotEmpty() && !rest.startsWith(" ")) return null
    return n to rest.trim()
}

private fun isRule(trimmed: String): Boolean {
    val s = trimmed.replace(" ", "")
    if (s.length < 3) return false
    return s.all { it == '-' } || s.all { it == '*' } || s.all { it == '_' }
}

private fun bulletContent(trimmed: String): String? {
    for (p in listOf("- ", "* ", "+ ")) if (trimmed.startsWith(p)) return trimmed.drop(2)
    return null
}

private fun orderedContent(trimmed: String): Pair<String, String>? {
    var idx = 0
    while (idx < trimmed.length && trimmed[idx].isDigit()) idx += 1
    if (idx == 0 || idx >= trimmed.length) return null
    val sep = trimmed[idx]
    if (sep != '.' && sep != ')') return null
    val after = idx + 1
    if (after >= trimmed.length || trimmed[after] != ' ') return null
    return "${trimmed.substring(0, idx)}." to trimmed.substring(after + 1)
}

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (!t.contains("-") || !t.contains("|")) return false
    return t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
}

private fun tableCells(line: String): List<String> {
    var t = line.trim()
    if (t.startsWith("|")) t = t.drop(1)
    if (t.endsWith("|")) t = t.dropLast(1)
    return t.split("|").map { it.trim() }
}
