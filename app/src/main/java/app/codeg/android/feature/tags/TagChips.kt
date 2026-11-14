package app.codeg.android.feature.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.ConversationTag

/** How big a chip is drawn: [ROW] inline after a list row's title, [REGULAR] elsewhere. */
enum class TagChipSize(val textSp: Int, val maxWidth: Dp, val hPad: Dp, val vPad: Dp) {
    ROW(10, 72.dp, 5.dp, 1.dp),
    REGULAR(12, 160.dp, 8.dp, 2.dp),
}

/** One tag as a chip, in its colour, readable in either theme ([TagSwatch]). */
@Composable
fun TagChip(name: String, color: String, size: TagChipSize = TagChipSize.REGULAR, modifier: Modifier = Modifier, branch: Boolean = false) {
    val colors = CodegTheme.colors
    val swatch = remember(color, colors.isDark) { TagSwatch.of(color, colors.isDark) }
    val bg = swatch?.let { Color(it.background) } ?: colors.surfaceStroke
    val fg = swatch?.let { Color(it.foreground) } ?: colors.textSecondary
    val border = swatch?.let { Color(it.border) } ?: Color.Transparent
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .widthIn(max = size.maxWidth)
            .clip(shape)
            .background(bg)
            .border(0.5.dp, border, shape)
            .padding(horizontal = size.hPad, vertical = size.vPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (branch) Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = fg, modifier = Modifier.size((size.textSp + 1).dp))
        Text(
            name,
            fontSize = size.textSp.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A neutral "+N" chip for the tags a row has no room for. */
@Composable
fun MoreTagsChip(count: Int, size: TagChipSize = TagChipSize.ROW) {
    val colors = CodegTheme.colors
    Text(
        "+$count",
        fontSize = size.textSp.sp,
        fontWeight = FontWeight.Medium,
        color = colors.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surfaceStroke)
            .padding(horizontal = size.hPad, vertical = size.vPad),
    )
}

/**
 * A row's chips: the branch first (it counts toward [limit], and never folds into "+N"), then
 * the tags in display order, the rest as "+N" (web `CARD_TAG_LIMIT`).
 */
@Composable
fun RowTagChips(tags: List<ConversationTag>, branch: String?, branchColor: String, limit: Int = 2) {
    if (tags.isEmpty() && branch == null) return
    val room = (limit - if (branch != null) 1 else 0).coerceAtLeast(0)
    val shown = tags.take(room)
    val hidden = tags.size - shown.size
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (branch != null) TagChip(branch, branchColor, TagChipSize.ROW, branch = true)
        shown.forEach { TagChip(it.name, it.color, TagChipSize.ROW) }
        if (hidden > 0) MoreTagsChip(hidden)
    }
}
