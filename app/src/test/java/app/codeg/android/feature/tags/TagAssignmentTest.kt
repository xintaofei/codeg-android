package app.codeg.android.feature.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagAssignmentTest {

    @Test
    fun `writes go one at a time, and a toggle made meanwhile goes in the next`() {
        val a = TagAssignment(emptySet())
        a.toggle(1)
        val first = a.nextWrite()!!
        assertEquals(listOf(1), first.add)
        a.toggle(2) // while the first is out
        a.succeeded(first, setOf(1))
        assertEquals(setOf(1, 2), a.desired)
        val second = a.nextWrite()!!
        assertEquals(listOf(2), second.add)
        a.succeeded(second, setOf(1, 2))
        assertNull(a.nextWrite())
    }

    @Test
    fun `a refused add does not undo the add the user made again since`() {
        val a = TagAssignment(emptySet())
        a.toggle(1) // add
        val first = a.nextWrite()!!
        a.toggle(1) // remove
        a.toggle(1) // add again
        a.failed(first)
        // The newest intent stands, and goes out next.
        assertEquals(setOf(1), a.desired)
        assertEquals(listOf(1), a.nextWrite()!!.add)
    }

    @Test
    fun `a refusal puts back what it sent and nothing else`() {
        val a = TagAssignment(setOf(3))
        a.toggle(3) // remove
        val write = a.nextWrite()!!
        assertEquals(listOf(3), write.remove)
        a.failed(write)
        assertEquals(setOf(3), a.desired)
        assertNull(a.nextWrite())
    }

    @Test
    fun `a success adopts the server's word for what nobody touched since`() {
        val a = TagAssignment(setOf(5))
        a.toggle(1)
        val write = a.nextWrite()!!
        a.toggle(2)
        // Another client took 5 off meanwhile.
        a.succeeded(write, setOf(1))
        assertEquals(setOf(1, 2), a.desired)
        assertEquals(setOf(1), a.confirmed)
    }

    @Test
    fun `a created tag is turned on once`() {
        val a = TagAssignment(setOf(1))
        a.turnOn(1)
        assertNull(a.nextWrite())
        a.turnOn(9)
        assertEquals(listOf(9), a.nextWrite()!!.add)
    }
}
