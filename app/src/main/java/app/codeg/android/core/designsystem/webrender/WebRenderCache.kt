package app.codeg.android.core.designsystem.webrender

import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Cache keys for the off-screen web renderer ([WebRenderHost]). A key is what the result
 * is a function of — the source's content, the theme, and for a picture the size it was
 * drawn at — and never a turn id (codeg turn ids are only unique within a conversation, so
 * a process-wide cache keyed by one would show one conversation's diagram in another). The
 * source enters as a SHA-256 digest so a key stays small however long the fence is.
 * Port of iOS `WebRenderKey`.
 */
object WebRenderKey {
    private const val SEP = '\u001F'

    fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Whole dp: a sub-dp change in a column's width must not miss. */
    fun points(value: Float): Int = if (!value.isFinite() || value <= 0f) 0 else value.roundToInt()

    /** The SVG of one source in one theme — independent of where it is drawn. */
    fun mermaid(source: String, theme: MermaidTheme): String = "mermaid$SEP${theme.name}$SEP${digest(source)}"

    /** The inline picture of that SVG at a column width and height cap (both in dp). */
    fun mermaidImage(source: String, theme: MermaidTheme, width: Float, clip: Float): String =
        "${mermaid(source, theme)}${SEP}w${points(width)}${SEP}c${points(clip)}"

    /** An HTML fence's document in one theme (its scheme, accent and surface). */
    fun htmlDocument(source: String, theme: ChatHtmlTheme): String = "html$SEP${theme.key}$SEP${digest(source)}"

    /** The preview of that document laid out at a column width, covering its first [clip] dp. */
    fun html(source: String, theme: ChatHtmlTheme, width: Float, clip: Float): String =
        "${htmlDocument(source, theme)}${SEP}w${points(width)}${SEP}c${points(clip)}"
}

/**
 * A small LRU bounded by entry count and by the bytes its values hold (the web's frame
 * cache: 32 entries / 8 MB of text). The newest entry is always kept, even alone over the
 * budget. Main-thread only, like everything the renderer touches.
 */
class ByteBoundedLru<V : Any>(
    val limit: Int,
    val maxBytes: Long,
    private val sizeOf: (V) -> Long,
) {
    private class Entry<V>(val value: V, val bytes: Long)

    // Access order: least recently used first.
    private val entries = LinkedHashMap<String, Entry<V>>(16, 0.75f, true)

    var totalBytes: Long = 0L
        private set

    val count: Int get() = entries.size

    operator fun get(key: String): V? = entries[key]?.value

    fun put(key: String, value: V) {
        remove(key)
        // UTF-16, as the web counts: two bytes a code unit, plus the key.
        val bytes = 128L + key.length * 2L + sizeOf(value)
        entries[key] = Entry(value, bytes)
        totalBytes += bytes
        val it = entries.entries.iterator()
        while ((entries.size > limit || totalBytes > maxBytes) && it.hasNext()) {
            val oldest = it.next()
            if (oldest.key == key) break
            totalBytes -= oldest.value.bytes
            it.remove()
        }
    }

    fun remove(key: String) {
        val removed = entries.remove(key) ?: return
        totalBytes -= removed.bytes
    }

    fun clear() {
        entries.clear()
        totalBytes = 0L
    }
}
