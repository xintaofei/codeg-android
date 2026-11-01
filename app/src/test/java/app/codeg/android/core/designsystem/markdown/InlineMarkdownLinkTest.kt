package app.codeg.android.core.designsystem.markdown

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineMarkdownLinkTest {
    @Test fun `APK links with spaces and balanced parentheses retain their full target`() {
        listOf(
            "[下载](</work/Android Project/app-debug.apk>) done" to "/work/Android Project/app-debug.apk",
            "[下载](/work/build(test)/app.apk) done" to "/work/build(test)/app.apk",
            "[下载](https://example.test/app.apk?v=2) done" to "https://example.test/app.apk?v=2",
        ).forEach { (raw, url) ->
            val parsed = inlineMarkdown(raw, Color.Blue, Color.Gray, Color.Black)
            assertEquals("下载 done", parsed.text)
            val link = parsed.getStringAnnotations(URL_TAG, 0, parsed.length).single()
            assertEquals(url, link.item)
            assertEquals(0, link.start)
            assertEquals(2, link.end)
        }
    }
}
