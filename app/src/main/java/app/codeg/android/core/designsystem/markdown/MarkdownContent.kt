package app.codeg.android.core.designsystem.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.core.designsystem.component.CodeBlock
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.webrender.HtmlPreviewBlock
import app.codeg.android.core.designsystem.webrender.MermaidBlock

/**
 * Block-level Markdown for assistant text — the Compose port of iOS
 * `MarkdownContent`. Splits the source into blocks (paragraphs, headings, lists,
 * quotes, fenced code, rules, GFM tables) so an agent's replies render like a
 * chat client. Inline spans within each block go through [rememberInlineMarkdown].
 */
@Composable
fun MarkdownContent(raw: String, modifier: Modifier = Modifier, streaming: Boolean = false) {
    // Slot-scoped parse only. The shared [MarkdownCache] is reserved for the persisted
    // node-build path; routing streaming callers (e.g. an auto-expanded ReasoningBlock)
    // through it would churn/evict persisted entries with dead growing-tail prefixes.
    val blocks = remember(raw) { parseMarkdownBlocks(raw) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Only the last block of text still arriving can still change.
        blocks.forEachIndexed { index, block -> SingleBlockView(block, streaming = streaming && index == blocks.lastIndex) }
    }
}

/**
 * Renders one already-parsed Markdown block. Extracted from the old private
 * `BlockView` so the session timeline can emit one LazyColumn item per block (a long
 * message is no longer a single non-lazy [Column]); [MarkdownContent] loops over it.
 */
@Composable
internal fun SingleBlockView(
    block: MarkdownBlock,
    modifier: Modifier = Modifier,
    /** This is the last block of text still arriving: a fence in it may not be final yet. */
    streaming: Boolean = false,
    /** Part of an assistant reply — the one place an HTML fence may render as a preview. */
    assistantReply: Boolean = false,
) {
    val colors = CodegTheme.colors
    when (block) {
        is MarkdownBlock.Paragraph -> Text(
            text = rememberInlineMarkdown(block.text),
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = colors.textPrimary,
            modifier = modifier.fillMaxWidth(),
        )

        is MarkdownBlock.Heading -> Text(
            text = rememberInlineMarkdown(block.text),
            fontSize = headingSize(block.level),
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            modifier = modifier.fillMaxWidth().padding(top = 2.dp),
        )

        is MarkdownBlock.BulletList -> Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (item in block.items) ListRow(marker = "•", content = item)
        }

        is MarkdownBlock.NumberedList -> Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for ((marker, content) in block.items) ListRow(marker = marker, content = content)
        }

        is MarkdownBlock.Quote -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(colors.accent.copy(alpha = 0.5f)),
            )
            Text(
                text = rememberInlineMarkdown(block.text),
                fontSize = 14.sp,
                lineHeight = 21.sp,
                fontStyle = FontStyle.Italic,
                color = colors.textSecondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is MarkdownBlock.Code -> when (val kind = MarkdownFence.kind(block, streaming, assistantReply, LocalChatHtmlRendering.current)) {
            is FenceKind.Mermaid -> MermaidBlock(block.code, kind.ready, modifier)
            FenceKind.HtmlPreview -> HtmlPreviewBlock(block.code, modifier)
            FenceKind.Code -> CodeBlock(code = block.code, modifier = modifier, language = block.language)
        }

        MarkdownBlock.Rule -> Box(
            modifier.fillMaxWidth().height(0.5.dp).padding(vertical = 2.dp).background(colors.hairline),
        )

        is MarkdownBlock.Table -> MarkdownTable(block, modifier)
    }
}

@Composable
private fun ListRow(marker: String, content: String) {
    val colors = CodegTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = marker,
            fontSize = 14.sp,
            color = colors.textTertiary,
            modifier = Modifier.width(16.dp),
        )
        Text(
            text = rememberInlineMarkdown(content),
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = colors.textPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MarkdownTable(table: MarkdownBlock.Table, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    Row(modifier.horizontalScroll(rememberScrollState())) {
        Column(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(colors.codeSurface)
                .border(0.5.dp, colors.hairline, RoundedCornerShape(10.dp)),
        ) {
            TableRow(table.header, isHeader = true)
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.hairline))
            table.rows.forEachIndexed { idx, cells ->
                TableRow(cells, isHeader = false)
                if (idx < table.rows.size - 1) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.hairline.copy(alpha = 0.5f)))
                }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, isHeader: Boolean) {
    val colors = CodegTheme.colors
    Row {
        for (cell in cells) {
            Text(
                text = rememberInlineMarkdown(cell),
                fontSize = 12.sp,
                fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isHeader) colors.textPrimary else colors.textSecondary,
                modifier = Modifier.width(130.dp).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

private fun headingSize(level: Int) = when (level) {
    1 -> 20.sp
    2 -> 18.sp
    3 -> 16.sp
    else -> 14.5.sp
}
