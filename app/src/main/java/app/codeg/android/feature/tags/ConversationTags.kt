package app.codeg.android.feature.tags

import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.ConversationTag
import app.codeg.android.core.model.FolderDetail
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The rules every tag surface shares (codeg web 0.44 `lib/conversation-tags.ts`): which tags a
 * conversation may carry, the order they are drawn in, the colours offered for new ones, and
 * what a tag filter matches. Pure, for tests.
 *
 * A tag with no folder is GLOBAL and fits every conversation, chat ones included; any other
 * belongs to one ROOT folder and fits only that folder's conversations and its worktrees'. Chat
 * folders never own tags.
 */
object ConversationTags {

    /** Longest name the server keeps (it trims and caps to this). */
    const val MAX_NAME_LENGTH = 64

    /**
     * The colours offered: a strong row and a soft row in the same hue order, each checked by the
     * web against the chip treatment below in both themes.
     */
    val COLOR_PRESETS = listOf(
        "#cf222e", "#bc4c00", "#9a6700", "#1a7f37", "#0f766e", "#0969da", "#8250df", "#bf3989", "#6e7781",
        "#fecaca", "#fed7aa", "#fde68a", "#bbf7d0", "#99f6e4", "#bfdbfe", "#ddd6fe", "#fbcfe8", "#e2e8f0",
    )
    private const val STRONG_COUNT = 9

    /**
     * The colour a NEW tag starts with: the first strong preset none of [neighbours] (the tags it
     * will be seen beside) uses yet; once all are taken, by count.
     */
    fun nextColor(neighbours: Collection<ConversationTag>): String {
        val used = neighbours.mapTo(HashSet()) { it.color.lowercase() }
        val strong = COLOR_PRESETS.take(STRONG_COUNT)
        return strong.firstOrNull { it !in used } ?: strong[neighbours.size % strong.size]
    }

    private val SHORT_HEX = Regex("^#([0-9a-fA-F]{3})$")
    private val LONG_HEX = Regex("^#[0-9a-fA-F]{6}$")

    /** `#rgb` / `#rrggbb` (any case) → lowercase `#rrggbb`, or null — the server's own rule. */
    fun normalizeColor(value: String): String? {
        val trimmed = value.trim()
        SHORT_HEX.find(trimmed)?.let { m -> return "#" + m.groupValues[1].map { "$it$it" }.joinToString("").lowercase() }
        return trimmed.takeIf { LONG_HEX.matches(it) }?.lowercase()
    }

    /** The folder whose tags a conversation in [folder] is offered: its root for a regular folder
     *  (worktrees follow their repo), none for a chat folder or an unknown one. */
    fun scopeFolderId(folder: FolderDetail?): Int? {
        if (folder == null || folder.isChatFolder) return null
        return folder.parentId ?: folder.id
    }

    /** Whether [tag] may go on a conversation whose folder scope is [scope]. */
    fun fitsScope(tag: ConversationTag, scope: Int?): Boolean = tag.folderId == null || tag.folderId == scope

    /** Display order: global first, then by folder, each scope in its order (ties on id). */
    val ORDER: Comparator<ConversationTag> = Comparator { a, b ->
        when {
            a.folderId != b.folderId -> when {
                a.folderId == null -> -1
                b.folderId == null -> 1
                else -> a.folderId.compareTo(b.folderId)
            }
            a.sortOrder != b.sortOrder -> a.sortOrder.compareTo(b.sortOrder)
            else -> a.id.compareTo(b.id)
        }
    }

    /**
     * [tagIds] as the tags this client knows, in display order. An unknown id is dropped: a tag
     * deleted elsewhere is not followed by per-conversation updates, so a summary can still name
     * it — and it must not be drawn, nor counted toward a "+N".
     */
    fun resolve(tagIds: List<Int>, byId: Map<Int, ConversationTag>): List<ConversationTag> =
        if (tagIds.isEmpty()) emptyList() else tagIds.mapNotNull { byId[it] }.sortedWith(ORDER)

    /** A case-insensitive name match within a scope (the server's uniqueness rule). */
    fun findByName(tags: Collection<ConversationTag>, scope: Int?, name: String): ConversationTag? {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return null
        return tags.firstOrNull { it.folderId == scope && it.name.lowercase() == key }
    }

    /** The chip a conversation's branch makes, when the branch tag is on and it has one. */
    fun branchChip(conversation: ConversationSummary, enabled: Boolean): String? =
        conversation.gitBranch?.trim()?.takeIf { enabled && it.isNotEmpty() && conversation.parentId == null }
}

/** `ANY`: a conversation matches with at least one selected tag; `ALL`: only with every one. */
enum class TagFilterMode { ANY, ALL }

/** The Chats list's tag filter, kept per server (tag ids are a server's own). */
@Serializable
data class TagFilter(val tagIds: List<Int> = emptyList(), val mode: TagFilterMode = TagFilterMode.ANY) {

    val isActive: Boolean get() = tagIds.isNotEmpty()

    /** Whether [conversation] passes. An inactive filter passes everything. */
    fun matches(conversation: ConversationSummary): Boolean {
        if (tagIds.isEmpty()) return true
        val own = conversation.tagIds
        if (own.isEmpty()) return false
        return if (mode == TagFilterMode.ALL) tagIds.all { it in own } else tagIds.any { it in own }
    }

    /** Toggle one tag, keeping the order picked. */
    fun toggle(tagId: Int): TagFilter =
        if (tagId in tagIds) copy(tagIds = tagIds - tagId) else copy(tagIds = tagIds + tagId)

    /**
     * Only tags that still exist: the filter outlives its tags (it is saved, and a tag can be
     * deleted elsewhere), and a dead id would turn `ALL` into "nothing matches" for no visible
     * reason. The same instance when nothing was dropped.
     */
    fun pruned(known: Set<Int>): TagFilter {
        val kept = tagIds.filter { it in known }
        return if (kept.size == tagIds.size) this else copy(tagIds = kept)
    }

    companion object {
        val NONE = TagFilter()
    }
}

/**
 * A tag's chip colours for both themes (web `labelSwatch`, GitHub's label treatment): light —
 * the colour as fill, black or white text by perceived lightness, a darker rim only for the
 * near-white; dark — the colour at 18% as fill, the colour itself lifted for the text. ARGB.
 */
data class TagSwatch(val background: Long, val foreground: Long, val border: Long) {

    companion object {
        private const val LIGHT_TEXT_THRESHOLD = 0.453
        private const val DARK_TEXT_THRESHOLD = 0.6
        private const val LIGHT_BORDER_THRESHOLD = 0.96
        private const val DARK_BACKGROUND_ALPHA = 0.18
        private const val DARK_BORDER_ALPHA = 0.3

        /** Null for a colour that isn't `#rrggbb` — the neutral chip then. */
        fun of(color: String, dark: Boolean): TagSwatch? {
            val hex = ConversationTags.normalizeColor(color) ?: return null
            val n = hex.substring(1).toInt(16)
            val r = (n shr 16) and 0xff
            val g = (n shr 8) and 0xff
            val b = n and 0xff
            val (h, s, l) = toHsl(r, g, b)
            val perceived = (r * 0.2126 + g * 0.7152 + b * 0.0722) / 255
            return if (!dark) {
                val borderAlpha = ((perceived - LIGHT_BORDER_THRESHOLD) * 100).coerceIn(0.0, 1.0)
                TagSwatch(
                    background = argb(1.0, r, g, b),
                    foreground = if (perceived < LIGHT_TEXT_THRESHOLD) 0xFFFFFFFF else 0xFF000000,
                    border = hsl(h, s, (l - 25).coerceIn(0.0, 100.0), borderAlpha),
                )
            } else {
                val lift = if (perceived < DARK_TEXT_THRESHOLD) (DARK_TEXT_THRESHOLD - perceived) * 100 else 0.0
                val textL = (l + lift).coerceIn(0.0, 100.0)
                TagSwatch(
                    background = argb(DARK_BACKGROUND_ALPHA, r, g, b),
                    foreground = hsl(h, s, textL, 1.0),
                    border = hsl(h, s, textL, DARK_BORDER_ALPHA),
                )
            }
        }

        /** Hue in degrees, saturation and lightness in percent. */
        private fun toHsl(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
            val rf = r / 255.0
            val gf = g / 255.0
            val bf = b / 255.0
            val max = maxOf(rf, gf, bf)
            val min = minOf(rf, gf, bf)
            val l = (max + min) / 2
            val span = max - min
            if (span == 0.0) return Triple(0.0, 0.0, l * 100)
            val s = span / (1 - abs(2 * l - 1))
            val h = when (max) {
                rf -> ((gf - bf) / span) % 6
                gf -> (bf - rf) / span + 2
                else -> (rf - gf) / span + 4
            }
            return Triple((((h * 60) % 360) + 360) % 360, s * 100, l * 100)
        }

        private fun hsl(h: Double, sPct: Double, lPct: Double, alpha: Double): Long {
            val s = sPct / 100
            val l = lPct / 100
            val c = (1 - abs(2 * l - 1)) * s
            val x = c * (1 - abs((h / 60) % 2 - 1))
            val m = l - c / 2
            val (r1, g1, b1) = when {
                h < 60 -> Triple(c, x, 0.0)
                h < 120 -> Triple(x, c, 0.0)
                h < 180 -> Triple(0.0, c, x)
                h < 240 -> Triple(0.0, x, c)
                h < 300 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
            return argb(alpha, ((r1 + m) * 255).roundToInt(), ((g1 + m) * 255).roundToInt(), ((b1 + m) * 255).roundToInt())
        }

        private fun argb(alpha: Double, r: Int, g: Int, b: Int): Long =
            ((alpha * 255).roundToInt().toLong() shl 24) or (r.coerceIn(0, 255).toLong() shl 16) or
                (g.coerceIn(0, 255).toLong() shl 8) or b.coerceIn(0, 255).toLong()
    }
}
