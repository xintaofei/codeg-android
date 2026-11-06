package app.codeg.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.BuildConfig
import app.codeg.android.core.designsystem.theme.CodegTheme

private const val NoticeAsset = "NOTICE_BUNDLE.txt"
private const val LinesPerChunk = 80

/** Read-only, selectable view of the notices packaged into the application. */
@Composable
fun OpenSourceLicensesScreen() {
    val context = LocalContext.current
    val colors = CodegTheme.colors
    val chunks = remember(context) {
        runCatching {
            buildString {
                append(context.assets.open(NoticeAsset).bufferedReader().use { it.readText() })
                if (BuildConfig.EASYTIER_ENABLED) {
                    for (asset in listOf("EASYTIER_NOTICE.txt", "EasyTier-LGPL-3.0.txt", "GPL-3.0.txt", "THIRD_PARTY_NOTICES.txt")) {
                        appendLine(); appendLine(asset)
                        append(context.assets.open(asset).bufferedReader().use { it.readText() })
                    }
                }
            }
        }.getOrNull()?.lineSequence()?.chunked(LinesPerChunk)?.map { lines ->
            lines.joinToString("\n")
        }?.toList().orEmpty()
    }

    if (chunks.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.settings_licenses_unavailable),
                color = colors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(chunks, key = { index, _ -> index }) { _, chunk ->
            SelectionContainer {
                Text(
                    text = chunk,
                    color = colors.textSecondary,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    ),
                )
            }
        }
    }
}
