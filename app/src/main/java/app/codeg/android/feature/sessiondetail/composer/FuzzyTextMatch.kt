package app.codeg.android.feature.sessiondetail.composer

/**
 * Ranked text matcher for the composer's slash-command / skill menus — a port of web
 * `src/lib/fuzzy-text-match.ts`. Tiers are exact → prefix → substring → subsequence, so a
 * short input like `bmhp` still hits `bmad-help`. Within a tier an earlier match position and
 * a shorter candidate win.
 *
 * Positions and lengths count UTF-16 code units, as JS does (and as a Kotlin String indexes),
 * so a ranking here is the ranking the web shows for the same list.
 */
internal object FuzzyTextMatch {
    private const val TIER_BASE = 100_000_000L
    private const val TIER_EXACT = 6
    private const val TIER_PREFIX = 5
    private const val TIER_SUBSTRING = 4
    private const val TIER_SUBSEQUENCE = 3

    private fun tierScore(tier: Int, position: Int, length: Int): Long =
        tier * TIER_BASE - minOf(position, 9_999) * 1_000L - minOf(length, 999)

    /** Index of the first matched unit if every unit of [query] appears in [s] in order, else -1. */
    fun subsequenceFirstIndex(query: String, s: String): Int {
        var qi = 0
        var firstIdx = -1
        var si = 0
        while (si < s.length && qi < query.length) {
            if (s[si] == query[qi]) {
                if (qi == 0) firstIdx = si
                qi++
            }
            si++
        }
        return if (qi == query.length) firstIdx else -1
    }

    /** Score one already-lowercased [text] against an already-lowercased, non-empty [query]. */
    fun score(query: String, text: String): Long? {
        if (query.isEmpty()) return null
        if (text == query) return tierScore(TIER_EXACT, 0, text.length)
        if (text.startsWith(query)) return tierScore(TIER_PREFIX, 0, text.length)
        val idx = text.indexOf(query)
        if (idx != -1) return tierScore(TIER_SUBSTRING, idx, text.length)
        val sub = subsequenceFirstIndex(query, text)
        if (sub != -1) return tierScore(TIER_SUBSEQUENCE, sub, text.length)
        return null
    }

    /**
     * Filter + rank [items] by a primary text, with an optional [secondary] field fallback. An
     * empty query returns the items unchanged. A secondary hit always ranks below every primary
     * hit, and equal scores keep their input order.
     */
    fun <T> rank(query: String, items: List<T>, primary: (T) -> String, secondary: ((T) -> String?)? = null): List<T> {
        val q = JsWhitespace.trim(query).lowercase()
        if (q.isEmpty()) return items
        // Below every primary tier, so a weak name subsequence still beats a perfect secondary match.
        val secondaryOffset = TIER_EXACT * TIER_BASE
        val scored = ArrayList<Pair<T, Long>>()
        for (item in items) {
            var value = score(q, primary(item).lowercase())
            if (value == null && secondary != null) {
                val text = secondary(item)?.lowercase()
                if (!text.isNullOrEmpty()) score(q, text)?.let { value = it - secondaryOffset }
            }
            value?.let { scored.add(item to it) }
        }
        // `sortedByDescending` is stable: the input order breaks ties, like the web's ES2019 sort.
        return scored.sortedByDescending { it.second }.map { it.first }
    }
}
