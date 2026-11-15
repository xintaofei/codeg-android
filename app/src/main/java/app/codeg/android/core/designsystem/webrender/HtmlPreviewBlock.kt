package app.codeg.android.core.designsystem.webrender

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodeBlock
import app.codeg.android.core.designsystem.component.CodegSegmented
import app.codeg.android.core.designsystem.markdown.LocalRichBlockGrew
import app.codeg.android.core.designsystem.theme.CodegTheme
import kotlinx.coroutines.CancellationException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val PreviewShape = RoundedCornerShape(10.dp)

/**
 * A closed ```` ```html ```` / ```` ```htm ```` fence in an assistant reply, with "Render HTML
 * in chat" on (codeg 0.44 web parity: `html-code-preview.tsx`; port of iOS `HtmlPreviewBlock`).
 *
 * - A Code / Preview switch, Preview first; the choice (and "Show all") is remembered per
 *   source ([HtmlPreviewViewMemory]), so the block comes back as it was when a reply settles
 *   into a new row.
 * - **Preview** is a static picture of the sanitized document laid out at the column's width
 *   ([ChatHtmlDocument], drawn off-screen by [WebRenderHost] with JavaScript off and every
 *   network load blocked). The document's height is clamped to 96–12,000 dp; 160 holds the
 *   space while it is drawn; past 640 it is cut until "Show all". Tap opens the live document
 *   full screen — still no script, no network.
 * - The theme (scheme + accent) is part of the cache key. A preview that cannot be drawn
 *   falls back to the code, as the web's does.
 */
@Composable
fun HtmlPreviewBlock(source: String, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val view = LocalView.current
    val density = LocalDensity.current.density
    val grew = LocalRichBlockGrew.current
    val theme = ChatHtmlTheme(
        dark = colors.isDark,
        accentId = Integer.toHexString(colors.accent.toArgb()),
        primary = Rgb.ofArgb(colors.accent.toArgb()),
        onPrimary = Rgb.ofArgb(colors.onAccent.toArgb()),
    )
    val remembered = remember(source) { HtmlPreviewViewMemory.shared.choice(source) }
    var preview by remember(source) { mutableStateOf(remembered.preview) }
    var expanded by remember(source) { mutableStateOf(remembered.expanded) }
    // Off once the reader switches the view or its height: the picture that lands for their
    // own choice must not move the transcript under them.
    var followsGrowth by remember(source) { mutableStateOf(true) }
    var widthPx by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<Pair<String, HtmlRendering>?>(null) }
    var failedKey by remember { mutableStateOf<String?>(null) }
    var viewer by remember { mutableStateOf(false) }

    val column = floor(widthPx / density)
    val request = if (column > 0f) {
        WebRenderHost.HtmlRequest(source, theme, column, HtmlPreviewMetrics.pictureClip(expanded))
    } else {
        null
    }

    fun rememberChoice() = HtmlPreviewViewMemory.shared.remember(HtmlPreviewViewMemory.Choice(preview, expanded), source)

    LaunchedEffect(if (preview) request else null) {
        val wanted = if (preview) request else null
        if (wanted == null) return@LaunchedEffect
        WebRenderHost.cachedHtml(wanted)?.let {
            shown = source to it
            failedKey = null
            return@LaunchedEffect
        }
        // The other view's picture may already cover this one: the full document holds its
        // first 640 dp, and a short document's collapsed picture is the whole of it.
        val otherClip = if (wanted.clip == HtmlPreviewMetrics.COLLAPSED_HEIGHT) HtmlPreviewMetrics.MAX_HEIGHT else HtmlPreviewMetrics.COLLAPSED_HEIGHT
        WebRenderHost.cachedHtml(wanted.copy(clip = otherClip))?.takeIf { it.covered >= min(it.height, wanted.clip) }?.let {
            shown = source to it
            failedKey = null
            return@LaunchedEffect
        }
        try {
            val rendering = WebRenderHost.html(wanted, view)
            shown = source to rendering
            failedKey = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedKey = WebRenderKey.htmlDocument(wanted.source, wanted.theme)
        }
        if (followsGrowth) grew()
    }

    Column(
        modifier.fillMaxWidth().onSizeChanged { widthPx = it.width },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Code, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                stringResource(R.string.rich_html_preview),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.width(150.dp)) {
                CodegSegmented(
                    options = listOf(stringResource(R.string.rich_html_code), stringResource(R.string.rich_html_preview_tab)),
                    selectedIndex = if (preview) 1 else 0,
                    onSelect = {
                        followsGrowth = false
                        preview = it == 1
                        rememberChoice()
                    },
                )
            }
        }
        if (!preview) {
            CodeBlock(code = source, language = "html")
        } else {
            val displayed = request?.let { r ->
                WebRenderHost.cachedHtml(r)
                    ?: WebRenderHost.cachedHtml(r.copy(clip = HtmlPreviewMetrics.pictureClip(!expanded)))
            } ?: shown?.takeIf { it.first == source }?.second
            when {
                displayed != null -> Picture(
                    displayed,
                    column,
                    expanded,
                    onOpen = { viewer = true },
                    onToggle = {
                        followsGrowth = false
                        expanded = !expanded
                        rememberChoice()
                    },
                )
                failedKey == WebRenderKey.htmlDocument(source, theme) -> CodeBlock(code = source, language = "html")
                else -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(HtmlPreviewMetrics.PLACEHOLDER_HEIGHT.dp)
                        .clip(PreviewShape)
                        .background(colors.bgElevated)
                        .border(0.5.dp, colors.hairline, PreviewShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = colors.textTertiary, strokeWidth = 2.dp)
                }
            }
        }
    }

    if (viewer) HtmlPreviewViewer(source, theme, onDismiss = { viewer = false })
}

@Composable
private fun Picture(rendering: HtmlRendering, column: Float, expanded: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val colors = CodegTheme.colors
    val image = remember(rendering) { rendering.bitmap.asImageBitmap() }
    // Drawn for another width (a rotation in progress): scaled to fit.
    val fit = if (column > 0f) min(1f, column / max(rendering.width, 1f)) else 1f
    val visible = min(HtmlPreviewMetrics.visibleHeight(rendering.height, expanded), rendering.covered)
    val drawingMore = expanded && rendering.covered < rendering.height
    val surface = Color(rendering.theme.surface.argb)
    val label = stringResource(R.string.rich_html_preview)
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height((visible * fit).dp)
                .clip(PreviewShape)
                .background(surface)
                .border(0.5.dp, colors.hairline, PreviewShape)
                .clickable(role = Role.Image, onClickLabel = stringResource(R.string.rich_view_fullscreen), onClick = onOpen)
                .semantics { contentDescription = label }
                .clipToBounds(),
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .size((rendering.width * fit).dp, (rendering.covered * fit).dp),
            )
            if (drawingMore) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.BottomCenter).padding(8.dp).size(16.dp),
                    color = colors.textTertiary,
                    strokeWidth = 2.dp,
                )
            }
        }
        if (HtmlPreviewMetrics.overflows(rendering.height)) {
            Text(
                stringResource(if (expanded) R.string.rich_html_collapse else R.string.rich_html_show_all),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onToggle)
                    .padding(vertical = 8.dp)
                    .wrapContentHeight(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
