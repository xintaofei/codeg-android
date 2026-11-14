package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * Message navigation (web `MessageNavList`): one row per message the user sent, numbered across
 * the whole conversation, the one being read marked. A tap takes the transcript there, paging
 * the history in first for one above what's loaded.
 *
 * [numbered] is false while the history above the window is unknown (an older server, or the
 * outline not read yet): the numbers would start in the wrong place, so rows show none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageNavSheet(
    entries: List<MessageNavEntry>,
    numbered: Boolean,
    outline: OutlineState,
    /** Older history exists that the outline would add. */
    hasEarlier: Boolean,
    currentTurnId: String?,
    running: Boolean,
    onRetryEarlier: () -> Unit,
    onPick: (MessageNavEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    val sheetState = rememberModalBottomSheetState()
    val listState = rememberLazyListState()
    val earlierRow = hasEarlier && outline !is OutlineState.Ready
    LaunchedEffect(Unit) {
        // Open on the message being read.
        val idx = entries.indexOfFirst { it.turnId == currentTurnId }.takeIf { it >= 0 } ?: entries.lastIndex
        if (idx > 0) listState.scrollToItem(idx + if (earlierRow) 1 else 0)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.bgElevated) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.message_nav_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, modifier = Modifier.weight(1f))
                if (numbered) {
                    Text(pluralStringResource(R.plurals.message_nav_total, entries.size, entries.size), fontSize = 12.sp, color = colors.textTertiary)
                }
            }
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 520.dp)) {
                if (earlierRow) {
                    item(key = "earlier") {
                        when (outline) {
                            OutlineState.Failed -> Row(
                                Modifier.fillMaxWidth().clickable(onClick = onRetryEarlier).padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                                Text(stringResource(R.string.message_nav_load_earlier), fontSize = 13.sp, color = colors.accent)
                            }
                            else -> Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(14.dp), color = colors.accent, strokeWidth = 1.5.dp)
                                Text(stringResource(R.string.message_nav_loading_earlier), fontSize = 13.sp, color = colors.textSecondary)
                            }
                        }
                    }
                }
                items(entries, key = { it.turnId }) { entry ->
                    val current = entry.turnId == currentTurnId
                    val last = entry === entries.last()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (current) colors.accent.copy(alpha = 0.1f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onPick(entry) }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .widthIn(min = 26.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (current) colors.accent else colors.surfaceStroke)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (numbered) "${entry.ordinal}" else "•",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (current) colors.onAccent else colors.textSecondary,
                            )
                        }
                        Text(
                            entry.label ?: stringResource(R.string.message_nav_untitled),
                            fontSize = 14.sp,
                            color = if (entry.label != null) colors.textPrimary else colors.textTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (last && running) stringResource(R.string.message_nav_running) else RelativeTime.compact(entry.sentAt),
                            fontSize = 11.sp,
                            color = if (last && running) colors.accent else colors.textTertiary,
                        )
                    }
                }
            }
        }
    }
}
