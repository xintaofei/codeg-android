package app.codeg.android.feature.sessiondetail.rendering

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CopyButton
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * Search output grouped per file instead of shown as a wall of text — the
 * Compose counterpart of the web `SearchResultsOutput`.
 *
 * Paths are shown, not opened: Android has no in-transcript file viewer for the
 * web's click-to-open-at-line affordance to land in.
 */
@Composable
fun SearchResultsView(parsed: SearchOutput.Parsed, query: String?, raw: String) {
    val colors = CodegTheme.colors

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.codeSurface)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary(parsed),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            CopyButton(raw)
        }
        HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)

        Column(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            parsed.groups.forEach { group ->
                if (parsed.mode == SearchOutput.Mode.FILES) {
                    PathRow(group.path)
                } else {
                    PathRow(group.path, heading = true)
                    group.lines.forEach { line -> MatchRow(line, query) }
                }
            }
        }

        if (parsed.hiddenCount > 0 || parsed.notes.isNotEmpty()) {
            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (parsed.hiddenCount > 0) {
                    Text(
                        stringResource(R.string.search_results_more, parsed.hiddenCount),
                        fontSize = 11.sp,
                        color = colors.textTertiary,
                    )
                }
                parsed.notes.forEach { note ->
                    Text(note, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun summary(parsed: SearchOutput.Parsed): String {
    val files = pluralStringResource(R.plurals.search_results_files, parsed.fileCount, parsed.fileCount)
    if (parsed.mode == SearchOutput.Mode.FILES) return files
    val matches = pluralStringResource(R.plurals.search_results_matches, parsed.matchCount, parsed.matchCount)
    return "$matches · $files"
}

@Composable
private fun PathRow(path: String, heading: Boolean = false) {
    val colors = CodegTheme.colors
    Text(
        text = path,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.5.sp,
        fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal,
        color = if (heading) colors.accent else colors.textSecondary,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = if (heading) 3.dp else 0.dp),
    )
}

@Composable
private fun MatchRow(line: SearchOutput.MatchLine, query: String?) {
    val colors = CodegTheme.colors
    val highlight = colors.accent.copy(alpha = 0.22f)
    // Leading indentation is dropped for display only — the copy action and the
    // fallback view still carry the process's bytes.
    val text = remember(line.text, query, highlight) {
        highlighted(line.text.trimStart(), query, highlight)
    }
    Row(Modifier.padding(start = 10.dp, end = 10.dp)) {
        Text(
            text = line.line?.toString().orEmpty(),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = colors.textTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp).padding(end = 8.dp),
        )
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = colors.textSecondary)
    }
}

/**
 * Occurrences of [query] tinted inside a matched line, matched case-insensitively
 * on the trimmed pattern.
 *
 * Literal matching only: the pattern is a regex on the agent's side, so a regex
 * query renders unhighlighted rather than lighting up the wrong characters. The
 * two caps keep a pathological pattern — one character against a minified line —
 * from flooding the row with spans.
 */
internal fun highlighted(
    text: String,
    query: String?,
    highlight: Color,
    maxHits: Int = 100,
    maxQueryLength: Int = 200,
): AnnotatedString {
    val needle = query?.trim()
    if (needle.isNullOrEmpty() || needle.length > maxQueryLength) return AnnotatedString(text)
    if (!text.contains(needle, ignoreCase = true)) return AnnotatedString(text)
    return buildAnnotatedString {
        var from = 0
        var hits = 0
        while (hits < maxHits) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            append(text.substring(from, at))
            // The line's own casing, not the query's.
            withStyle(SpanStyle(background = highlight)) {
                append(text.substring(at, at + needle.length))
            }
            from = at + needle.length
            hits += 1
        }
        append(text.substring(from))
    }
}
