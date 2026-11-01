package app.codeg.android.feature.downloads

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class LocalFilePreviewTest {
    @Test fun `code and images preview locally while executable and active formats stay external`() {
        listOf("a.kt", "a.JSON", "a.tsx", "README", "Dockerfile", ".gitignore", "a.md").forEach {
            assertEquals(it, FilePreviewKind.TEXT, filePreviewKind(it))
        }
        assertEquals(FilePreviewKind.IMAGE, filePreviewKind("picture.PNG"))
        listOf("app.apk", "file.pdf", "picture.svg", "unknown.bin").forEach {
            assertEquals(it, FilePreviewKind.EXTERNAL, filePreviewKind(it))
        }
    }

    @Test fun `UTF8 UTF16 BOMs and ordinary unicode text are preserved`() {
        val text = "你好😀\nval value = 1"
        listOf(text.toByteArray(), byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)).forEach {
            assertEquals(TextFilePreview(text, false), readTextPreview(ByteArrayInputStream(it)))
        }
        assertEquals(TextFilePreview("", false), readTextPreview(ByteArrayInputStream(byteArrayOf())))
    }

    @Test fun `bounded preview tolerates a cutoff inside a UTF8 character and never reads the full file`() {
        val bytes = ("a".repeat(MAX_PREVIEW_BYTES - 1) + "😀" + "z".repeat(MAX_PREVIEW_BYTES)).toByteArray()
        val input = ByteArrayInputStream(bytes)
        val preview = readTextPreview(input)
        assertTrue(preview.truncated)
        assertEquals("a".repeat(MAX_PREVIEW_BYTES - 1), preview.text)
        assertEquals(bytes.size - MAX_PREVIEW_BYTES - 1, input.available())
    }

    @Test fun `line limit truncates the preview without changing the downloaded file`() {
        val preview = readTextPreview(ByteArrayInputStream((1..2100).joinToString("\n").toByteArray()))
        assertTrue(preview.truncated)
        assertEquals(2000, preview.text.lines().size)
        assertTrue(preview.text.endsWith("2000"))
    }

    @Test fun `binary and malformed text cannot be silently displayed as code`() {
        listOf(byteArrayOf(1, 0, 2), byteArrayOf(0xC3.toByte(), 0x28)).forEach {
            try { readTextPreview(ByteArrayInputStream(it)); fail("Accepted binary text") } catch (_: Exception) { }
        }
    }
}
