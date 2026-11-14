package app.codeg.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Recognising the server's "conversation moved" prompt refusal (`AcpError::ConversationMoved`). */
class ConversationMovedTest {

    private fun server(message: String, code: String? = "invalid_input") = ApiError.Server(400, code, message)

    @Test
    fun `the refusal names the folder the conversation moved to`() {
        assertEquals(
            ConversationMoved(42),
            server("conversation moved to another folder (folder 42)").conversationMoved,
        )
    }

    @Test
    fun `an elaborated message without the folder is still recognised`() {
        assertEquals(ConversationMoved(null), server("conversation moved to another folder").conversationMoved)
    }

    @Test
    fun `other refusals are not a move`() {
        assertNull(server("no active turn to send feedback to").conversationMoved)
        assertNull(ApiError.TurnInProgress.conversationMoved)
        assertNull(ApiError.Transport("conversation moved to another folder (folder 1)").conversationMoved)
    }
}
