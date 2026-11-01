package app.codeg.android.feature.downloads

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

internal enum class FilePreviewKind { IMAGE, TEXT, EXTERNAL }

internal fun filePreviewKind(filename: String): FilePreviewKind {
    val extension = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        extension in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif") -> FilePreviewKind.IMAGE
        extension in setOf("txt", "md", "markdown", "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "json", "jsonl",
            "xml", "html", "htm", "css", "scss", "less", "csv", "tsv", "yaml", "yml", "toml", "ini", "conf", "config",
            "properties", "log", "sh", "bash", "zsh", "bat", "ps1", "php", "rb", "rs", "go", "c", "h", "cpp", "hpp",
            "swift", "m", "mm", "dart", "sql", "vue", "svelte", "gradle", "gitignore", "env", "rst", "tex") ||
            filename.lowercase(Locale.ROOT) in setOf("dockerfile", "makefile", "license", "readme") -> FilePreviewKind.TEXT
        else -> FilePreviewKind.EXTERNAL
    }
}

internal data class TextFilePreview(val text: String, val truncated: Boolean)
internal const val MAX_PREVIEW_BYTES = 128 * 1024
internal const val MAX_PREVIEW_LINES = 2000

/** A bounded read, including UTF-8/UTF-16 BOMs and a cutoff inside a multibyte character. */
internal fun readTextPreview(input: InputStream): TextFilePreview {
    val buffer = ByteArray(MAX_PREVIEW_BYTES + 1)
    var used = 0
    while (used < buffer.size) {
        val count = input.read(buffer, used, buffer.size - used)
        if (count < 0) break
        if (count == 0) {
            val byte = input.read()
            if (byte < 0) break
            buffer[used++] = byte.toByte()
        } else used += count
    }
    val truncated = used > MAX_PREVIEW_BYTES
    val size = minOf(used, MAX_PREVIEW_BYTES)
    val (charset, bom) = when {
        size >= 3 && buffer[0] == 0xEF.toByte() && buffer[1] == 0xBB.toByte() && buffer[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        size >= 2 && buffer[0] == 0xFF.toByte() && buffer[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        size >= 2 && buffer[0] == 0xFE.toByte() && buffer[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> Charsets.UTF_8 to 0
    }
    val output = CharBuffer.allocate(size + 1)
    val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val result = decoder.decode(ByteBuffer.wrap(buffer, bom, size - bom), output, !truncated)
    if (result.isError) result.throwException()
    output.flip()
    val text = output.toString()
    require(text.none { it == '\u0000' }) { "Binary file" }
    val lines = text.lineSequence().take(MAX_PREVIEW_LINES + 1).toList()
    return TextFilePreview(lines.take(MAX_PREVIEW_LINES).joinToString("\n"), truncated || lines.size > MAX_PREVIEW_LINES)
}
