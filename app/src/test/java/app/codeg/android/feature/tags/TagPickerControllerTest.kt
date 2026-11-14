package app.codeg.android.feature.tags

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.ConversationTag
import app.codeg.android.core.model.FolderDetail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TagPickerControllerTest {

    private val tag1 = ConversationTag(1, null, "one", "#cf222e")

    private fun conv(id: Int, tags: List<Int> = emptyList()) = ConversationSummary(
        id = id, folderId = 1, agentType = AgentType.CODEX, status = ConversationStatus.COMPLETED,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, tagIds = tags,
    )

    /** A server whose tag writes are answered by hand, in the order the test chooses. */
    private inner class FakeServer(val name: String) : TagsApi {
        val writes = mutableListOf<Triple<Int, List<Int>, List<Int>>>()
        val pending = ArrayDeque<CompletableDeferred<ConversationSummary>>()
        val held = mutableMapOf<Int, MutableSet<Int>>()
        override suspend fun listTags() = listOf(tag1)
        override suspend fun listFolders() = listOf(FolderDetail(id = 1, name = "f", path = "/f"))
        var createGate: CompletableDeferred<Unit>? = null
        override suspend fun createTag(folderId: Int?, name: String, color: String): ConversationTag {
            createGate?.await()
            return ConversationTag(9, folderId, name, color)
        }
        override suspend fun setTags(conversationId: Int, add: List<Int>, remove: List<Int>): ConversationSummary {
            writes += Triple(conversationId, add, remove)
            val answer = CompletableDeferred<ConversationSummary>()
            pending.addLast(answer)
            return answer.await()
        }

        /** Answer the oldest write as the server would. */
        fun answer() {
            val (id, add, remove) = writes[writes.size - pending.size]
            val set = held.getOrPut(id) { mutableSetOf() }
            set.removeAll(remove.toSet())
            set.addAll(add)
            pending.removeFirst().complete(conv(id, set.sorted()))
        }
    }

    private fun TestScope.controller(connect: suspend () -> Pair<String, TagsApi>?) =
        TagPickerController(backgroundScope, connect, onChanged = {}, failure = { _, e -> e.message.orEmpty() })

    @Test
    fun `an older open resuming late doesn't bind over a newer one`() = runTest {
        val a = FakeServer("a")
        val b = FakeServer("b")
        val slow = CompletableDeferred<Unit>()
        var calls = 0
        val c = controller {
            calls += 1
            if (calls == 1) {
                slow.await()
                "a" to a
            } else {
                "b" to b
            }
        }
        c.open(conv(7))
        runCurrent()
        c.open(conv(7))
        runCurrent()
        slow.complete(Unit) // the first open's server answers last
        runCurrent()
        c.toggle(tag1)
        runCurrent()
        assertTrue(a.writes.isEmpty())
        assertEquals(listOf(Triple(7, listOf(1), emptyList<Int>())), b.writes)
    }

    @Test
    fun `reopening a conversation still writing takes over its one writer`() = runTest {
        val server = FakeServer("s")
        val c = controller { "s" to server }
        c.open(conv(1))
        runCurrent()
        c.toggle(tag1) // add, held by the server
        runCurrent()
        c.open(conv(2))
        runCurrent()
        c.open(conv(1)) // the caller's summary doesn't know of the add yet
        runCurrent()
        assertEquals(setOf(1), c.ui.value.tagIds)
        c.toggle(tag1) // remove: waits for the add rather than racing it
        runCurrent()
        assertEquals(1, server.writes.size)
        server.answer()
        runCurrent()
        assertEquals(Triple(1, emptyList<Int>(), listOf(1)), server.writes.last())
        server.answer()
        runCurrent()
        assertEquals(emptySet<Int>(), c.ui.value.tagIds)
        assertEquals(emptySet<Int>(), server.held[1])
    }

    @Test
    fun `a switch to another server starts over there`() = runTest {
        val x = FakeServer("x")
        val y = FakeServer("y")
        var current: Pair<String, TagsApi> = "x" to x
        val c = controller { current }
        c.open(conv(3))
        runCurrent()
        current = "y" to y
        c.open(conv(3))
        runCurrent()
        c.toggle(tag1)
        runCurrent()
        assertTrue(x.writes.isEmpty())
        assertEquals(1, y.writes.size)
    }

    @Test
    fun `nothing toggles before this opening has read the tags`() = runTest {
        val server = FakeServer("s")
        val gate = CompletableDeferred<Unit>()
        val c = controller { gate.await(); "s" to server }
        c.open(conv(4))
        c.toggle(tag1)
        gate.complete(Unit)
        runCurrent()
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun `a tag created as the picker moves on still goes on its conversation`() = runTest {
        val server = FakeServer("s")
        val c = controller { "s" to server }
        c.open(conv(5))
        runCurrent()
        c.create("new", global = true)
        c.open(conv(6)) // before the create lands
        runCurrent()
        assertEquals(Triple(5, listOf(9), emptyList<Int>()), server.writes.single())
    }

    @Test
    fun `a reopen while a tag is being created keeps the one writer`() = runTest {
        val server = FakeServer("s")
        val gate = CompletableDeferred<Unit>()
        server.createGate = gate
        val c = controller { "s" to server }
        c.open(conv(8))
        runCurrent()
        c.create("new", global = true)
        runCurrent()
        c.open(conv(9))
        runCurrent()
        c.open(conv(8)) // the create is still out
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        c.toggle(tag1)
        runCurrent()
        // The created tag's write first; the toggle waits for it instead of racing it.
        assertEquals(listOf(Triple(8, listOf(9), emptyList<Int>())), server.writes)
        server.answer()
        runCurrent()
        assertEquals(Triple(8, listOf(1), emptyList<Int>()), server.writes.last())
        server.answer()
        runCurrent()
        assertEquals(setOf(1, 9), c.ui.value.tagIds)
    }
}
