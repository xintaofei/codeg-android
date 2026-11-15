package app.codeg.android.core.designsystem.webrender

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodeBlock
import app.codeg.android.core.designsystem.markdown.LocalRichBlockGrew
import app.codeg.android.core.designsystem.theme.CodegTheme
import kotlinx.coroutines.CancellationException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val BlockShape = RoundedCornerShape(10.dp)

/** Between the card's edge and the picture. */
private const val INSET_DP = 8f

/** What holds the space while the diagram is drawn. */
private val PlaceholderHeight = 112.dp

/**
 * A ```` ```mermaid ```` fence, drawn as a diagram (codeg 0.44 web parity: `mermaid-block.tsx`;
 * port of iOS `MermaidBlock`).
 *
 * - **When.** Only once [ready] — the fence closed, or the text stopped streaming. Until then
 *   (and while the off-screen renderer works) a placeholder of fixed height holds the space.
 *   The web redraws on every streamed delta; this client draws once.
 * - **Inline.** A static picture of the diagram, fitted to the column (never enlarged), at
 *   most 60% of the window tall, its clipped edge faded. Tap opens it full screen;
 *   long-press copies the source.
 * - **Theme.** Mermaid's `dark` / `default` theme follows the color scheme and is part of the
 *   cache key; on a switch the old picture stays until the new one is drawn.
 * - **Errors.** "Mermaid error: …" with Retry, and the source behind "Show source". Failures
 *   are not cached, so Retry draws again.
 *
 * All state is this leaf's own; the transcript re-parses streaming text every flush and the
 * effect's key — source, theme and size — does not change across those passes, so nothing is
 * drawn twice.
 */
@Composable
fun MermaidBlock(source: String, ready: Boolean, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val theme = MermaidTheme(colors.isDark)
    val view = LocalView.current
    val density = LocalDensity.current.density
    val grew = LocalRichBlockGrew.current
    val screenHeight = LocalConfiguration.current.screenHeightDp
    // The web's `max-h-[60vh]`.
    val maxHeight = max(200f, (screenHeight * 0.6f).roundToInt().toFloat())

    var widthPx by remember { mutableIntStateOf(0) }
    val column = floor(widthPx / density - 2 * INSET_DP)
    val request = if (column > 0f) WebRenderHost.MermaidRequest(source, theme, column, maxHeight) else null

    var shown by remember { mutableStateOf<Pair<String, MermaidRendering>?>(null) }
    var failure by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var viewer by remember { mutableStateOf<MermaidRendering?>(null) }

    LaunchedEffect(if (ready) request else null, attempt) {
        val wanted = if (ready) request else null
        if (wanted == null) return@LaunchedEffect
        WebRenderHost.cachedMermaid(wanted)?.let {
            shown = source to it
            failure = null
            return@LaunchedEffect
        }
        try {
            val rendering = WebRenderHost.mermaid(wanted, view)
            shown = source to rendering
            failure = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebRenderError.Mermaid) {
            failure = wanted.diagramKey to e.detail.ifEmpty { null }
        } catch (e: Exception) {
            failure = wanted.diagramKey to null
        }
        grew()
    }

    Box(modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
        val displayed = request?.let(WebRenderHost::cachedMermaid)
            ?: shown?.takeIf { it.first == source }?.second
            ?: WebRenderHost.latestMermaid(source, theme)
        val failed = failure?.takeIf { it.first == WebRenderKey.mermaid(source, theme) }
        when {
            !ready -> Placeholder()
            displayed != null -> Diagram(displayed, source, column, onOpen = { viewer = displayed })
            failed != null -> ErrorCard(failed.second, source, onRetry = {
                failure = null
                attempt += 1
            })
            else -> Placeholder()
        }
    }

    viewer?.let { rendering ->
        MermaidViewer(source, rendering.diagram, rendering.theme, onDismiss = { viewer = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Diagram(rendering: MermaidRendering, source: String, column: Float, onOpen: () -> Unit) {
    val colors = CodegTheme.colors
    val context = LocalContext.current
    val surface = Color(RichBlockPalette.surface(rendering.theme.dark).argb)
    val image = remember(rendering) { rendering.bitmap.asImageBitmap() }
    // Drawn for another width (a rotation in progress): scaled to fit.
    val fit = if (column > 0f) min(1f, column / max(rendering.size.width, 1f)) else 1f
    val width = max(1f, floor(rendering.size.width * fit))
    val height = max(1f, floor(rendering.size.height * fit))
    val label = stringResource(R.string.rich_diagram)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(BlockShape)
            .background(surface)
            .border(0.5.dp, colors.hairline, BlockShape)
            .combinedClickable(
                role = Role.Image,
                onClickLabel = stringResource(R.string.rich_view_fullscreen),
                onLongClickLabel = stringResource(R.string.rich_copy_source),
                onLongClick = { copySource(context, source) },
                onClick = onOpen,
            )
            .semantics { contentDescription = label }
            .padding(INSET_DP.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.High,
                modifier = Modifier.size(width.dp, height.dp),
            )
            if (rendering.clipped) {
                // The cap cuts a tall diagram mid-stroke: fade the edge so it reads as
                // "there is more", not as a fault.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .size(width.dp, 40.dp)
                        .background(Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface))),
                )
            }
        }
        Icon(
            Icons.Rounded.OpenInFull,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clip(CircleShape)
                .background(colors.bg.copy(alpha = 0.85f))
                .padding(5.dp)
                .size(11.dp),
        )
    }
}

@Composable
private fun Placeholder() {
    val colors = CodegTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(PlaceholderHeight)
            .clip(BlockShape)
            .background(colors.bgElevated)
            .border(0.5.dp, colors.hairline, BlockShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.rich_mermaid_rendering), fontSize = 13.sp, color = colors.textTertiary)
    }
}

@Composable
private fun ErrorCard(message: String?, source: String, onRetry: () -> Unit) {
    val colors = CodegTheme.colors
    var showSource by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(BlockShape)
            .background(colors.danger.copy(alpha = 0.06f))
            .border(0.75.dp, colors.danger.copy(alpha = 0.3f), BlockShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.rich_mermaid_error, message ?: stringResource(R.string.rich_mermaid_error_generic)),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = colors.danger,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 32.dp)) {
                Text(stringResource(R.string.common_retry), fontSize = 12.sp, color = colors.textPrimary)
            }
        }
        Row(
            Modifier.clickable(role = Role.Button) { showSource = !showSource },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(14.dp).rotate(if (showSource) 90f else 0f),
            )
            Text(
                stringResource(if (showSource) R.string.rich_hide_source else R.string.rich_show_source),
                fontSize = 12.sp,
                color = colors.textTertiary,
            )
        }
        AnimatedVisibility(visible = showSource) {
            CodeBlock(code = source, language = "mermaid")
        }
    }
}
