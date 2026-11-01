package app.codeg.android.core.designsystem.markdown

/**
 * Bounded LRU cache for parsed Markdown blocks, used ONLY by the persisted node-build
 * path. A long assistant message is split into per-block timeline nodes at build time,
 * and the transcript re-builds whenever `ui.turns` changes (send / completion /
 * reattach) — without a cache that means re-scanning every persisted message's markdown
 * on each background timeline build. Keyed by the message text (stable → String.hashCode is
 * JVM-cached after the first lookup), so unchanged messages are parsed once.
 *
 * The growing *live* text is deliberately NOT cached here (it flows through
 * [LiveBlockParser] instead); a per-flush key would churn and evict useful persisted
 * entries. `@Synchronized` protects the cache across background timeline builds.
 */
object MarkdownCache {
    private const val MAX_BLOCKS = 256

    private val blockCache = object : LinkedHashMap<String, List<MarkdownBlock>>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<MarkdownBlock>>): Boolean = size > MAX_BLOCKS
    }

    /** Block-split [raw], memoized. For stable (persisted) text only — NOT the live tail. */
    @Synchronized
    fun blocks(raw: String): List<MarkdownBlock> =
        blockCache.getOrPut(raw) { parseMarkdownBlocks(raw) }
}
