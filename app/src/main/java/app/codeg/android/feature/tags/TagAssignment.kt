package app.codeg.android.feature.tags

/**
 * One conversation's tags as the user wants them ([desired]) and as the server last confirmed
 * them ([confirmed]), with the writes between the two sent one at a time. Pure, for tests.
 *
 * Every toggle stamps its tag with a rising clock. A write carries the clock it was computed at,
 * so its outcome only settles the tags no one has touched since: a success adopts the server's
 * word for them (another client's change included), a failure puts them back where the server
 * has them — and a toggle made while the write was out stands either way, to go in the next.
 */
class TagAssignment(confirmed: Set<Int>) {

    /** What one request sends, and the clock it was computed at. */
    data class Write(val add: List<Int>, val remove: List<Int>, val at: Long)

    var confirmed: Set<Int> = confirmed
        private set

    private val wanted = confirmed.toMutableSet()
    private val touched = HashMap<Int, Long>()
    private var clock = 0L

    val desired: Set<Int> get() = wanted.toSet()

    fun toggle(tagId: Int) {
        if (!wanted.add(tagId)) wanted.remove(tagId)
        touched[tagId] = ++clock
    }

    /** Make sure [tagId] is on (a tag just created for this conversation). */
    fun turnOn(tagId: Int) {
        if (tagId !in wanted) toggle(tagId)
    }

    /** What to send next, or null when the server already has what is wanted. */
    fun nextWrite(): Write? {
        val add = (wanted - confirmed).sorted()
        val remove = (confirmed - wanted).sorted()
        if (add.isEmpty() && remove.isEmpty()) return null
        return Write(add, remove, clock)
    }

    /** The server applied [write] and now holds [server]. */
    fun succeeded(write: Write, server: Set<Int>) {
        val untouched = (wanted + confirmed + server).filter { untouchedSince(write, it) }
        confirmed = server
        for (id in untouched) if (id in server) wanted.add(id) else wanted.remove(id)
    }

    /** The server refused [write]: what it sent, untouched since, goes back. */
    fun failed(write: Write) {
        for (id in write.add + write.remove) {
            if (!untouchedSince(write, id)) continue
            if (id in confirmed) wanted.add(id) else wanted.remove(id)
        }
    }

    private fun untouchedSince(write: Write, id: Int): Boolean = (touched[id] ?: 0L) <= write.at
}
