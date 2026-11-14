package app.codeg.android.feature.sessions

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.model.FolderVisibility
import app.codeg.android.core.model.MoveConversationsResult

/**
 * "Move to" for one conversation (web `MoveToFolderMenu` + `MoveConversationDialog`): pick one of
 * the other open folders, then confirm. [folders] is the server's open-folder list (exactly the
 * valid targets), null while it loads; [allFolders] names a worktree by its repo; a chat's
 * [currentFolderPath] is its scratch directory, where its files stay behind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveConversationSheet(
    conversation: ConversationSummary,
    folders: List<FolderDetail>?,
    allFolders: List<FolderDetail>,
    currentFolderPath: String?,
    onMove: (FolderDetail) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    var confirm by remember { mutableStateOf<FolderDetail?>(null) }
    val target = confirm
    if (target == null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.bgElevated) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.move_conversation_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                val targets = folders?.filter { it.id != conversation.folderId && !it.isChat }
                when {
                    targets == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent, strokeWidth = 2.dp)
                    }
                    targets.isEmpty() -> Text(
                        stringResource(R.string.move_conversation_no_targets),
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                        items(targets, key = { it.id }) { folder ->
                            FolderTargetRow(folder, allFolders) { confirm = folder }
                        }
                    }
                }
            }
        }
    } else {
        val title = conversation.trimmedTitle ?: stringResource(R.string.move_conversation_untitled)
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = colors.bgElevated,
            title = { Text(stringResource(R.string.move_conversation_title), color = colors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.move_conversation_intro, title), fontSize = 14.sp, color = colors.textSecondary)
                    Column {
                        Text(folderLabel(target, allFolders), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                        Text(target.path, fontSize = 12.sp, color = colors.textTertiary)
                    }
                    Text(stringResource(R.string.move_conversation_explain), fontSize = 13.sp, color = colors.textSecondary)
                    if (conversation.kind == "chat" && currentFolderPath != null) {
                        Text(stringResource(R.string.move_conversation_chat_files, currentFolderPath), fontSize = 13.sp, color = colors.textSecondary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onMove(target); onDismiss() }) {
                    Text(stringResource(R.string.move_conversation_move), color = colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = colors.textSecondary) }
            },
        )
    }
}

@Composable
private fun FolderTargetRow(folder: FolderDetail, all: List<FolderDetail>, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (FolderVisibility.isWorktree(folder)) Icons.Rounded.AccountTree else Icons.Rounded.Folder,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(folderLabel(folder, all), fontSize = 14.sp, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(folder.path, fontSize = 12.sp, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A worktree reads as its repo and branch — its own name is usually a generated directory. */
private fun folderLabel(folder: FolderDetail, all: List<FolderDetail>): String {
    val name = FolderVisibility.displayName(folder, all)
    val branch = folder.gitBranch?.takeIf { FolderVisibility.isWorktree(folder) && it.isNotBlank() }
    return if (branch != null) "$name · $branch" else name
}

/** What a move did, as one line: how many went where, then why the others didn't (each distinct
 *  reason once). */
fun moveOutcomeText(context: Context, result: MoveConversationsResult, folderName: String): String {
    val res = context.resources
    val parts = mutableListOf<String>()
    if (result.moved.isNotEmpty()) {
        parts += res.getQuantityString(R.plurals.move_conversation_moved, result.moved.size, result.moved.size, folderName)
    }
    if (result.skipped.isNotEmpty()) {
        val reasons = result.skipped.map { it.reason }.distinct().joinToString("; ") { context.getString(moveSkipReasonRes(it)) }
        parts += res.getQuantityString(R.plurals.move_conversation_skipped, result.skipped.size, result.skipped.size) + ": " + reasons
    }
    return parts.joinToString("\n")
}

/** The skip reason, as the web words it. */
fun moveSkipReasonRes(reason: String): Int = when (reason) {
    "not_found" -> R.string.move_skip_not_found
    "not_movable" -> R.string.move_skip_not_movable
    "already_there" -> R.string.move_skip_already_there
    "task_owned" -> R.string.move_skip_task_owned
    "busy" -> R.string.move_skip_busy
    "conflict" -> R.string.move_skip_conflict
    "target_unavailable" -> R.string.move_skip_target_unavailable
    else -> R.string.move_skip_unknown
}
