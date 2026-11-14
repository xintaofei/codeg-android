package app.codeg.android.feature.sessiondetail.timeline

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.designsystem.markdown.MarkdownContent
import app.codeg.android.core.designsystem.markdown.SingleBlockView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.feature.sessiondetail.rendering.InlineImage
import app.codeg.android.feature.sessiondetail.rendering.LivePlanView
import app.codeg.android.feature.sessiondetail.rendering.ReasoningBlock
import app.codeg.android.feature.sessiondetail.rendering.ToolCallCard
import app.codeg.android.feature.sessiondetail.rendering.ToolGroupCard
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val SuccessGreen = Color(0xFF85D18F)

/** Scrolls the timeline to a node id — used by the footer's jump-to-question button. */
val LocalTimelineScroll = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Renders a node's content to the right of the rail. A pure dispatcher that reuses
 * the existing render leaves verbatim — the timeline is only a new shell. Port of
 * the iOS `NodeBody`.
 */
@Composable
fun NodeBody(node: TimelineNode, modifier: Modifier = Modifier) {
    when (val c = node.content) {
        is NodeContent.User -> UserNodeBody(c.turn, modifier)
        is NodeContent.System -> SystemNodeBody(c.turn, modifier)
        is NodeContent.AssistantBlock ->
            if (c.streaming) {
                Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SingleBlockView(c.block)
                    BlinkingCaret()
                }
            } else {
                SingleBlockView(c.block, modifier)
            }
        is NodeContent.Reasoning -> ReasoningBlock(c.text, modifier, initiallyExpanded = c.streaming)
        is NodeContent.Tool -> ToolCallCard(c.vm, modifier)
        is NodeContent.ToolGroup -> ToolGroupCard(c.items, modifier, streaming = c.streaming, disclosureKey = node.id)
        is NodeContent.Image -> InlineImage(c.image, c.caption, modifier)
        is NodeContent.Compaction -> ContextCompactionDivider(c.before, c.after, c.running, modifier)
        is NodeContent.Footer -> TurnFooter(c.turn, c.questionId, modifier)
        is NodeContent.Plan -> LivePlanView(c.entries, modifier)
        is NodeContent.Thinking -> ThinkingShimmer(modifier)
        is NodeContent.Error -> InlineTurnError(c.message, c.details, modifier)
        is NodeContent.ReplyHeader -> ReplyHeaderBody(c, modifier)
        is NodeContent.ReplyFoot -> ReplyFootBody(c, modifier)
        is NodeContent.Unsupported -> Text(
            stringResource(R.string.timeline_unsupported_block, c.type),
            fontSize = 11.sp,
            color = CodegTheme.colors.textTertiary,
            modifier = modifier,
        )
    }
}

/** The user's prompt — a left-aligned faint-accent card filling the content column. */
@Composable
private fun UserNodeBody(turn: MessageTurn, modifier: Modifier) {
    val colors = CodegTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.accent.copy(alpha = 0.12f))
            .border(0.5.dp, colors.accent.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (block in turn.blocks) {
            when (block) {
                is ContentBlock.Text -> if (block.text.isNotBlank()) MarkdownContent(block.text)
                is ContentBlock.Image -> InlineImage(block.image, null)
                is ContentBlock.ImageGeneration ->
                    if (block.image != null) InlineImage(block.image, block.revisedPrompt)
                    else if (!block.revisedPrompt.isNullOrBlank()) MarkdownContent(block.revisedPrompt)
                else -> {}
            }
        }
    }
}

/** A system note — a dim, card-less log line. */
@Composable
private fun SystemNodeBody(turn: MessageTurn, modifier: Modifier) {
    val text = remember(turn) { systemText(turn) }
    Text(
        text,
        fontSize = 12.sp,
        color = CodegTheme.colors.textTertiary,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Per-reply action + metadata row: Copy · jump-to-question · model · time/tokens. */
@Composable
private fun TurnFooter(turn: MessageTurn, questionId: String?, modifier: Modifier) {
    val colors = CodegTheme.colors
    // Copy text joins every block → memoize it. Meta stays live so the relative time
    // ("47m") keeps ticking rather than freezing at whatever it was when first composed.
    val copyText = remember(turn) {
        turn.blocks
            .mapNotNull { (it as? ContentBlock.Text)?.text?.takeIf { t -> t.isNotBlank() } }
            .joinToString("\n\n")
    }
    val meta = buildList {
        add(RelativeTime.compact(turn.completedAt ?: turn.timestamp))
        val tokens = turn.usage?.total ?: 0
        if (tokens > 0) add(compactTokens(tokens))
        // The time the reply took is its header's (see [ReplyHeaderBody]).
    }.joinToString(" · ")

    Row(
        modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (copyText.isNotEmpty()) CopyButton(copyText)
        if (questionId != null) JumpToQuestionButton(questionId)
        Spacer(Modifier.weight(1f))
        turn.model?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(meta, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = colors.textTertiary, maxLines = 1)
    }
}

/** [modifier] lets a caller outside the dense footer row grow the hit area to a real target
 *  (the footer's own is sized to sit inline with 10sp metadata). */
@Composable
private fun CopyButton(text: String, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { clipboard.setText(AnnotatedString(text)); copied = true }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Crossfade(copied, label = "copy-icon") { c ->
            Icon(
                if (c) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = null,
                tint = if (c) SuccessGreen else colors.textTertiary,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(if (copied) stringResource(R.string.timeline_copied) else stringResource(R.string.timeline_copy), fontSize = 11.sp, color = colors.textTertiary)
    }
}

@Composable
private fun JumpToQuestionButton(questionId: String) {
    val scroll = LocalTimelineScroll.current
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { scroll(questionId) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            Icons.Rounded.ArrowUpward,
            contentDescription = stringResource(R.string.timeline_scroll_to_question),
            tint = CodegTheme.colors.textTertiary,
            modifier = Modifier.size(13.dp),
        )
        Text(stringResource(R.string.timeline_question), fontSize = 11.sp, color = CodegTheme.colors.textTertiary)
    }
}

/** Three pulsing dots shown before the agent's first token. Port of iOS `ThinkingShimmer`. */
@Composable
fun ThinkingShimmer(modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(
        modifier.padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0..2) {
            val a by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse, StartOffset(i * 180)),
                label = "dot-$i",
            )
            Spacer(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.textTertiary.copy(alpha = a)),
            )
        }
    }
}

/** A blinking text cursor trailing streamed prose. */
@Composable
private fun BlinkingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val a by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(530), RepeatMode.Reverse),
        label = "caret-alpha",
    )
    Text("▌", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = CodegTheme.colors.accent, modifier = Modifier.alpha(a))
}

/**
 * The context-compaction boundary: a hairline running the content width with a small
 * archive glyph + label centred on it. Deliberately chrome-less (no card, no border) —
 * it marks "the conversation's context was compacted here", it is not something the
 * agent *did*.
 *
 * Grok stamps the before/after token counts on its compaction; codex sends none, and a
 * no-op delta (before == after) would read as a bug, so both fall back to the plain
 * label.
 */
@Composable
fun ContextCompactionDivider(before: Int?, after: Int?, running: Boolean, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val label = when {
        running -> stringResource(R.string.compaction_running)
        before != null && after != null && before != after ->
            stringResource(R.string.compaction_done_tokens, groupDigits(before), groupDigits(after))
        else -> stringResource(R.string.compaction_done)
    }
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp).alpha(if (running) 0.65f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = colors.hairline)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(Icons.Rounded.Archive, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(13.dp))
            Text(label, fontSize = 11.sp, color = colors.textTertiary)
        }
        HorizontalDivider(Modifier.weight(1f), color = colors.hairline)
    }
}

/** Thousands separators for the token counts, in the user's locale. */
private fun groupDigits(value: Int): String = String.format(java.util.Locale.getDefault(), "%,d", value)

/**
 * A danger banner for a turn-tail error / cancellation. Port of iOS `InlineTurnError`.
 *
 * [details] is the server's diagnostic evidence for an error codeg inferred — the agent's
 * stderr tail and what codeg failed to parse. It stays behind a disclosure and out of the
 * message line: it is multi-line raw agent output, and only wanted once the user asks why.
 */
@Composable
fun InlineTurnError(message: String, details: String? = null, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.danger.copy(alpha = 0.10f))
            .border(0.5.dp, colors.danger.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.WarningAmber, null, tint = colors.danger, modifier = Modifier.size(14.dp))
            Text(message, fontSize = 12.sp, color = colors.danger, modifier = Modifier.weight(1f))
        }
        details?.takeIf { it.isNotBlank() }?.let { ErrorDetailsDisclosure(it) }
    }
}

/** Guard against a pathological stderr tail. The server bounds this already, but that
 *  bound is its own — not one this card's layout agreed to. */
private const val MAX_ERROR_DETAIL_CHARS = 4_000

@Composable
private fun ErrorDetailsDisclosure(details: String) {
    val colors = CodegTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.timeline_error_details)
    val stateLabel = stringResource(
        if (expanded) R.string.common_expanded else R.string.common_collapsed,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                // The label is 11sp to sit under the error line without competing with it,
                // which leaves the text itself well under the 48dp minimum target. Grow the
                // hit area instead of the type: the row is inside a scrolling transcript, so
                // a target that has to be aimed at costs a scroll every miss.
                .heightIn(min = 44.dp)
                .padding(horizontal = 6.dp)
                // The chevron carries the state visually and is decorative, so the row that is
                // actually clickable has to carry it for a screen reader. `stateDescription`,
                // not `toggleableState`: the latter is announced as checked/unchecked, which
                // describes a checkbox rather than a section that opens.
                .semantics {
                    contentDescription = label
                    stateDescription = stateLabel
                    role = Role.Button
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.danger.copy(alpha = 0.85f))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.danger.copy(alpha = 0.85f),
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.bgElevated.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    details.take(MAX_ERROR_DETAIL_CHARS),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    softWrap = false,
                )
                // Copying a stderr tail into a bug report is the reason this is on screen at
                // all. The server's containment rule is that the payload must not be
                // FORWARDED automatically to anything outliving the app (an OS notification,
                // a persisted tooltip) — it is redacted at the source precisely so it can be
                // shown here, and handed to the clipboard only when the user asks.
                CopyButton(details.take(MAX_ERROR_DETAIL_CHARS), Modifier.heightIn(min = 44.dp))
            }
        }
    }
}

private fun compactTokens(n: Int): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> trimNum(n / 1_000.0) + "k"
    else -> trimNum(n / 1_000_000.0) + "M"
}

private fun trimNum(v: Double): String = if (v >= 100) v.roundToInt().toString() else String.format("%.1f", v)

