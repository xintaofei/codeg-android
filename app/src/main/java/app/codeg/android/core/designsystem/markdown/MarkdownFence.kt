package app.codeg.android.core.designsystem.markdown

/**
 * How a fenced code block is drawn. Most fences are code; a few labels claim the fence
 * for a renderer of its own (codeg 0.44 web parity, iOS `MarkdownFence`):
 *
 * - ```` ```mermaid ```` — a diagram, in any Markdown (the web installs its `pre` override
 *   on every Streamdown). Drawn only once its source is final: the web re-renders a diagram
 *   on every streamed delta, this client renders it once, when the fence has closed or the
 *   text stopped streaming.
 * - ```` ```html ```` / ```` ```htm ```` — an HTML preview, in an assistant reply only, when
 *   the "Render HTML in chat" setting is on, and only once the fence has closed (a fence the
 *   reply was cut off in stays code). Raw HTML outside a fence stays text.
 *
 * PlantUML stays code; json-render's ```` ```spec ```` is not drawn here either.
 */
sealed interface FenceKind {
    /** [ready] once the source can no longer change. */
    data class Mermaid(val ready: Boolean) : FenceKind
    data object HtmlPreview : FenceKind
    data object Code : FenceKind
}

object MarkdownFence {
    /** The fence's language: the first word of its info string (mdast `lang`). */
    fun language(info: String?): String? {
        if (info == null) return null
        val word = info.trimStart(' ', '\t').takeWhile { it != ' ' && it != '\t' }
        return word.ifEmpty { null }
    }

    /**
     * `language-mermaid` and nothing else — case-sensitive, as the web's `MERMAID_LANGUAGE`
     * class match is; `mermaidx` and `mermaid-lite` are code.
     */
    fun isMermaid(info: String?): Boolean = language(info) == "mermaid"

    /** `/^html?$/i` on the fence's language (`chat-html-markdown.ts`). */
    fun isHtml(info: String?): Boolean {
        val word = language(info)?.lowercase() ?: return false
        return word == "html" || word == "htm"
    }

    /**
     * Whether a fence's source is final. Once the text has stopped streaming it is, closed or
     * not. While it streams, the fence has to be closed and its closing line finished: a
     * closing fence that is still the last thing received may yet turn out to be a longer
     * run of backticks or a line of code (the web waits for the line ending the same way).
     */
    fun isSettled(closed: Boolean, streaming: Boolean, isLastBlock: Boolean, closingLineEnded: Boolean): Boolean {
        if (!streaming) return true
        if (!closed) return false
        return !isLastBlock || closingLineEnded
    }

    /**
     * The renderer for a fence. [settled] is [isSettled]; [htmlEnabled] the per-device
     * "Render HTML in chat" setting.
     */
    fun kind(
        info: String?,
        code: String,
        closed: Boolean,
        settled: Boolean,
        assistantReply: Boolean,
        htmlEnabled: Boolean = false,
    ): FenceKind {
        if (isMermaid(info)) return FenceKind.Mermaid(ready = settled)
        if (!assistantReply) return FenceKind.Code
        if (htmlEnabled && closed && settled && isHtml(info) && code.any { !it.isWhitespace() }) {
            return FenceKind.HtmlPreview
        }
        return FenceKind.Code
    }

    /** [kind] for a parsed block, [streaming] when it is the last block of text still arriving. */
    fun kind(block: MarkdownBlock.Code, streaming: Boolean, assistantReply: Boolean, htmlEnabled: Boolean): FenceKind =
        kind(
            info = block.language,
            code = block.code,
            closed = block.closed,
            settled = isSettled(block.closed, streaming, isLastBlock = true, closingLineEnded = block.closingLineEnded),
            assistantReply = assistantReply,
            htmlEnabled = htmlEnabled,
        )
}
