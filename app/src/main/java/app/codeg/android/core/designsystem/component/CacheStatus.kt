package app.codeg.android.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import java.text.DateFormat
import java.util.Date

/** Small, non-scrolling status shared by the conversation list and transcript. */
@Composable
fun CacheStatus(
    refreshing: Boolean,
    showingCached: Boolean,
    lastUpdatedAt: Long?,
    modifier: Modifier = Modifier,
) {
    if (!refreshing && !showingCached && lastUpdatedAt == null) return
    val updated = lastUpdatedAt?.let {
        val formatted = remember(it) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        }
        stringResource(R.string.cache_updated_at, formatted)
    }
    val message = when {
        refreshing -> stringResource(R.string.cache_refreshing)
        showingCached -> listOfNotNull(stringResource(R.string.cache_local_copy), updated).joinToString(" · ")
        else -> updated.orEmpty()
    }
    Text(
        text = message,
        style = MaterialTheme.typography.labelSmall,
        color = CodegTheme.colors.textTertiary,
        modifier = modifier,
    )
}
