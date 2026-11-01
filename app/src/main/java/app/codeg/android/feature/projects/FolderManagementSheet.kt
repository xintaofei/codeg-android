package app.codeg.android.feature.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderVisibility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderManagementSheet(
    folders: List<FolderDetail>,
    hiddenIds: Set<Int>,
    onSetHidden: (Int, Boolean) -> Unit,
    onMove: (Int, Int) -> Unit,
    onReorder: (List<Int>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    error: String?,
) {
    val colors = CodegTheme.colors
    val hidden = remember(folders, hiddenIds) { FolderVisibility.hiddenIds(folders, hiddenIds) }
    val listState = rememberLazyListState()
    var displayed by remember(folders) { mutableStateOf(folders) }
    var draggedId by remember(folders) { mutableStateOf<Int?>(null) }
    var dragTop by remember { mutableStateOf(0f) }
    var fingerY by remember { mutableStateOf(0f) }
    var dragHeight by remember { mutableStateOf(0) }
    val saveOrder by rememberUpdatedState(onReorder)
    val haptic = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(draggedId) {
        if (draggedId == null) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val from = displayed.indexOfFirst { it.id == draggedId }
            val current = info.visibleItemsInfo.firstOrNull { it.key == draggedId }
            val center = (dragTop + dragHeight / 2f)
                .coerceIn(info.viewportStartOffset.toFloat(), (info.viewportEndOffset - 1).toFloat())
            val target = info.visibleItemsInfo.firstOrNull {
                it.key != draggedId && center >= it.offset && center < it.offset + it.size
            }
            if (from >= 0 && current?.index == from && target != null && displayed.getOrNull(target.index)?.id == target.key) {
                // Keep the viewport fixed while the first visible item changes places.
                listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                displayed = displayed.toMutableList().apply { add(target.index, removeAt(from)) }
            }
            val scroll = when {
                listState.canScrollBackward && fingerY < info.viewportStartOffset + edge -> ((fingerY - info.viewportStartOffset - edge) / 5f).coerceAtLeast(-edge / 3f)
                listState.canScrollForward && fingerY > info.viewportEndOffset - edge -> ((fingerY - info.viewportEndOffset + edge) / 5f).coerceAtMost(edge / 3f)
                else -> 0f
            }
            if (scroll != 0f) listState.scrollBy(scroll)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bgElevated,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.folders_manage), style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
            Text(stringResource(R.string.folders_management_description), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            error?.let { Text(it, color = colors.danger, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(
                state = listState,
                // Programmatic edge scrolling must not let the list steal the held pointer.
                userScrollEnabled = draggedId == null,
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 440.dp).pointerInput(folders) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { position ->
                            listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                position.y >= it.offset && position.y < it.offset + it.size
                            }?.let {
                                draggedId = it.key as Int
                                dragTop = it.offset.toFloat()
                                fingerY = position.y
                                dragHeight = it.size
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { change, amount ->
                            if (draggedId != null) {
                                change.consume()
                                dragTop += amount.y
                                fingerY += amount.y
                            }
                        },
                        onDragEnd = {
                            if (draggedId != null && displayed != folders) saveOrder(displayed.map { it.id })
                            draggedId = null
                        },
                        onDragCancel = { displayed = folders; draggedId = null },
                    )
                },
            ) {
                itemsIndexed(displayed, key = { _, folder -> folder.id }) { index, folder ->
                    val dragging = draggedId == folder.id
                    val isHidden = folder.id in hidden
                    val inheritedHidden = folder.parentId in hidden
                    val upLabel = stringResource(R.string.folders_move_up, folder.name)
                    val downLabel = stringResource(R.string.folders_move_down, folder.name)
                    val placement = if (dragging) Modifier.zIndex(1f).graphicsLayer {
                        translationY = dragTop - (listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == folder.id }?.offset ?: dragTop.toInt())
                        shadowElevation = 6.dp.toPx()
                    } else Modifier.animateItem()
                    Row(placement.fillMaxWidth().height(64.dp)
                        .background(if (dragging) colors.accentDim else colors.bgElevated, RoundedCornerShape(8.dp))
                        .semantics(mergeDescendants = true) {
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction(upLabel) { onMove(folder.id, -1); true })
                                if (index < displayed.lastIndex) add(CustomAccessibilityAction(downLabel) { onMove(folder.id, 1); true })
                            }
                        }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(folder.name, color = if (isHidden) colors.textTertiary else colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (inheritedHidden) stringResource(R.string.folders_hidden_with_parent) else folder.path, color = colors.textTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(
                            enabled = !inheritedHidden && draggedId == null,
                            onClick = { onSetHidden(folder.id, !isHidden) },
                        ) {
                            Icon(
                                if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                stringResource(if (isHidden) R.string.folders_show_named else R.string.folders_hide_named, folder.name),
                                tint = if (isHidden) colors.textTertiary else colors.accent,
                            )
                        }
                        Icon(Icons.Rounded.DragHandle, stringResource(R.string.folders_drag, folder.name), tint = colors.textTertiary)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onReset) { Text(stringResource(R.string.folders_reset_order)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
            }
        }
    }
}
