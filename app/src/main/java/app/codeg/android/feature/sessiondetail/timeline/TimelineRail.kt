package app.codeg.android.feature.sessiondetail.timeline

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.component.SectionBadgeIcon
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.feature.sessiondetail.rendering.LocalActivityLive
import app.codeg.android.feature.sessiondetail.rendering.ToolCallState
import app.codeg.android.feature.sessiondetail.rendering.ToolWarning
import app.codeg.android.feature.sessiondetail.rendering.ToolKindBucket
import app.codeg.android.feature.sessiondetail.rendering.bucketIcon

private val SuccessGreen = Color(0xFF85D18F)

/** Structural metrics for the timeline rail. Port of the iOS `TimelineMetrics`. */
object TimelineMetrics {
    val gutterWidth: Dp = 30.dp
    val markerSize: Dp = 26.dp
    val lineWidth: Dp = 1.5.dp
    val rowSpacing: Dp = 18.dp          // split as top/bottom padding so the spine is continuous
    val groupGap: Dp = 10.dp            // extra top space above a new-turn (user/system) node
    val gutterContentGap: Dp = 10.dp    // gutter → content
    val markerGap: Dp = 3.dp            // clearance between marker edge and where the spine resumes
    val blockGap: Dp = 8.dp             // tight gap between a split message's block rows (≈ MarkdownContent spacedBy)
}

/**
 * How a row participates in the rail. A long assistant message is split into one
 * LazyColumn item per Markdown block: the [Head] carries the avatar marker, the
 * [Middle]/[Tail] rows are markerless and drawn against one continuous spine with
 * tight spacing so the reply still reads as a single unit. [Standalone] is a
 * one-block message (or any non-split node) — the original behavior.
 */
enum class RailStyle { Standalone, Head, Middle, Tail }

/** What a node draws in the gutter. Port of the iOS `MarkerKind`. */
sealed interface MarkerKind {
    data object User : MarkerKind
    data class Assistant(val agent: AgentType) : MarkerKind
    data object Reasoning : MarkerKind
    /** [callId]: so the marker can turn amber while a permission request waits on the call. */
    data class Tool(val bucket: ToolKindBucket, val state: ToolCallState, val callId: String? = null) : MarkerKind
    data class ToolGroup(val error: Boolean, val streaming: Boolean, val unsettledIds: List<String> = emptyList()) : MarkerKind
    data object Image : MarkerKind
    data object System : MarkerKind
    data object Plan : MarkerKind
    data object Thinking : MarkerKind
    data object Error : MarkerKind
    data object Footer : MarkerKind
    /** A context-compaction boundary. Like [Footer] it is a *marker on* the spine, not
     *  an event hanging off it — the divider body carries the glyph and the label, so
     *  the gutter just marks the point. */
    data object Compaction : MarkerKind

    /** A note of the agent's on the rail: the reply's [Assistant] marker is on its header. */
    data object Prose : MarkerKind

    /** The foot folding a reply's steps back: like [Compaction], a mark ON the spine. */
    data object Fold : MarkerKind
}

/**
 * One timeline row: a fixed gutter (marker + the continuous spine drawn behind) and
 * the node body to its right. The spine is drawn with [drawBehind] *before* padding
 * so it spans the inter-row gap; the line is skipped in a gap around the marker so
 * the translucent disc reads cleanly over the app background. Port of the iOS
 * `TimelineRailRow`.
 */
@Composable
fun TimelineRow(
    marker: MarkerKind,
    connectTop: Boolean,
    connectBottom: Boolean,
    startsGroup: Boolean,
    modifier: Modifier = Modifier,
    rail: RailStyle = RailStyle.Standalone,
    content: @Composable () -> Unit,
) {
    val railColor = CodegTheme.colors.rail
    val hasMarker = rail == RailStyle.Standalone || rail == RailStyle.Head
    val continuation = rail == RailStyle.Middle || rail == RailStyle.Tail
    // A split message's continuation rows sit tight (like MarkdownContent's 8dp block
    // spacing) and carry no marker; head/standalone keep the normal inter-node spacing.
    val topPad = when (rail) {
        RailStyle.Standalone, RailStyle.Head ->
            TimelineMetrics.rowSpacing / 2 + (if (startsGroup) TimelineMetrics.groupGap else 0.dp)
        RailStyle.Middle, RailStyle.Tail -> 0.dp
    }
    val bottomPad = when (rail) {
        RailStyle.Standalone, RailStyle.Tail -> TimelineMetrics.rowSpacing / 2
        RailStyle.Head, RailStyle.Middle -> TimelineMetrics.blockGap
    }

    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val centerX = TimelineMetrics.gutterWidth.toPx() / 2f
                val markerCenterY = topPad.toPx() + TimelineMetrics.markerSize.toPx() / 2f
                // Continuation rows draw one solid spine (gap 0) so blocks read as one
                // reply; marker'd rows carve a clear disc around the glyph. A compaction
                // is a marker ON the spine, not an event hanging off it, so the rail runs
                // straight through it too.
                val runsThrough = continuation || marker is MarkerKind.Compaction || marker is MarkerKind.Fold
                val gap = if (runsThrough) 0f else TimelineMetrics.markerSize.toPx() / 2f + TimelineMetrics.markerGap.toPx()
                val w = TimelineMetrics.lineWidth.toPx()
                if (connectTop) {
                    drawLine(railColor, Offset(centerX, -1f), Offset(centerX, markerCenterY - gap), w)
                }
                if (connectBottom) {
                    drawLine(railColor, Offset(centerX, markerCenterY + gap), Offset(centerX, size.height + 1f), w)
                }
            }
            .padding(top = topPad, bottom = bottomPad),
        verticalAlignment = Alignment.Top,
    ) {
        // Continuation rows leave the gutter childless so row height is driven by content
        // (an empty 26dp marker box would floor every block row at 26dp).
        Box(Modifier.width(TimelineMetrics.gutterWidth), contentAlignment = Alignment.TopCenter) {
            if (hasMarker) NodeMarker(marker)
        }
        Spacer(Modifier.width(TimelineMetrics.gutterContentGap))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** The gutter glyph for a node. Port of the iOS `NodeMarker`. */
@Composable
fun NodeMarker(kind: MarkerKind, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val size = TimelineMetrics.markerSize
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        when (kind) {
            is MarkerKind.User -> FilledDisc(Icons.Rounded.Person, colors.accent)
            is MarkerKind.Assistant -> AgentAvatar(kind.agent, size = size)
            is MarkerKind.Reasoning -> SectionBadgeIcon(Icons.Rounded.Psychology, colors.textTertiary, size = size)
            is MarkerKind.Tool -> {
                val running = kind.state == ToolCallState.RUNNING || kind.state == ToolCallState.INPUT_STREAMING
                // Waiting on the user's approval, the call isn't at work: no ping.
                val awaiting = running && kind.callId != null && kind.callId == LocalActivityLive.current.awaitingToolCallId
                val tint = when {
                    awaiting -> ToolWarning
                    running -> colors.accent
                    kind.state == ToolCallState.DONE -> SuccessGreen
                    else -> colors.danger
                }
                SectionBadgeIcon(bucketIcon(kind.bucket), tint, size = size)
                if (running && !awaiting) PulseRing(tint, size)
            }
            is MarkerKind.ToolGroup -> {
                val awaitedId = LocalActivityLive.current.awaitingToolCallId
                val awaiting = awaitedId != null && awaitedId in kind.unsettledIds
                val tint = when {
                    awaiting -> ToolWarning
                    kind.error -> colors.danger
                    else -> colors.accent
                }
                SectionBadgeIcon(Icons.Rounded.Layers, tint, size = size)
                if (kind.streaming && !awaiting) PulseRing(colors.accent, size)
            }
            is MarkerKind.Image -> SectionBadgeIcon(Icons.Rounded.Image, colors.textSecondary, size = size)
            is MarkerKind.System ->
                Box(Modifier.size(size * 0.34f).clip(CircleShape).background(colors.textTertiary.copy(alpha = 0.55f)))
            is MarkerKind.Plan -> SectionBadgeIcon(Icons.Rounded.Checklist, colors.accent, size = size)
            is MarkerKind.Thinking -> if (LocalActivityLive.current.awaitingUser) {
                SectionBadgeIcon(Icons.Rounded.MoreHoriz, ToolWarning, size = size)
            } else {
                SectionBadgeIcon(Icons.Rounded.MoreHoriz, colors.accent, size = size)
                PulseRing(colors.accent, size)
            }
            is MarkerKind.Error -> FilledDisc(Icons.Rounded.PriorityHigh, colors.danger)
            // The divider body already carries the archive glyph and the label; the
            // gutter just marks the point on the spine.
            is MarkerKind.Compaction, is MarkerKind.Fold ->
                Box(Modifier.size(size * 0.34f).clip(CircleShape).background(colors.rail))
            is MarkerKind.Prose ->
                Box(Modifier.size(size * 0.28f).clip(CircleShape).background(colors.textTertiary.copy(alpha = 0.75f)))
            is MarkerKind.Footer ->
                Box(
                    Modifier.size(size * 0.34f).clip(CircleShape)
                        .background(colors.bg)
                        .border(1.5.dp, colors.rail, CircleShape),
                )
        }
    }
}

/** A solid-tint disc with a contrast glyph — used for the user + error markers. */
@Composable
private fun FilledDisc(icon: ImageVector, fill: Color) {
    val size = TimelineMetrics.markerSize
    val glyph = if (fill.luminance() > 0.6f) Color(0xFF0F0F0F) else Color.White
    Box(Modifier.size(size).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = glyph, modifier = Modifier.size(size * 0.46f))
    }
}

/** An outward "radar ping" ring (scale 1→1.75, fade 0.7→0) for live markers. */
@Composable
private fun PulseRing(tint: Color, size: Dp) {
    val transition = rememberInfiniteTransition(label = "pulse-ring")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "pulse-progress",
    )
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                val s = 1f + progress * 0.75f
                scaleX = s
                scaleY = s
                alpha = (1f - progress) * 0.7f
            }
            .border(TimelineMetrics.lineWidth, tint, CircleShape),
    )
}
