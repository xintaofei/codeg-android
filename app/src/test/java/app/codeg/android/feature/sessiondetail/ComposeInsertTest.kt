package app.codeg.android.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeInsertTest {

    @Test
    fun `a command runs as slash name`() {
        assertEquals("/review ", ComposeInsert.appendCommand("review", ""))
        assertEquals("fix this /review ", ComposeInsert.appendCommand("review", "fix this  "))
    }

    @Test
    fun `a codex skill keeps its dollar prefix`() {
        assertEquals("\$deploy", ComposeInsert.invocationToken("\$deploy"))
        assertEquals("\$deploy ", ComposeInsert.appendCommand("\$deploy", ""))
        assertEquals("ship it \$deploy ", ComposeInsert.appendCommand("\$deploy", "ship it"))
    }
}
