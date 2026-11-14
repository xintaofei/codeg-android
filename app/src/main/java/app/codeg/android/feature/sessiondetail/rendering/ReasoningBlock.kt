package app.codeg.android.feature.sessiondetail.rendering

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.markdown.MarkdownContent
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.sessiondetail.timeline.LocalDisclosures

/**
 * The agent's reasoning between its calls (web `ThoughtRow`): one line — `Thought  Checking the
 * auth flow ⌄` — named by what it is about ([ThoughtTitle]), opening onto the full text. While
 * it is written it reads `Thinking`, pulsing, under its newest heading.
 *
 * Folded by default: opening a thought is the reader's call, and it never opens or closes
 * itself as the block streams. The open panel is the reasoning minus the heading the row
 * already shows. [disclosureKey] keeps the choice above the list, so a row scrolled away and
 * back (or rebuilt by every streamed token) keeps it.
 */
@Composable
fun ReasoningBlock(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    disclosureKey: String? = null,
) {
    val colors = CodegTheme.colors
    val title = remember(text, streaming) { ThoughtTitle.title(text, streaming) }
    val body = remember(text, title) { ThoughtTitle.bodyWithoutTitle(text, title).trim() }
    val store = LocalDisclosures.current
    val shared = disclosureKey?.takeIf { store != null }?.let { "thought:$it" }
    var ownExpanded by remember(disclosureKey) { mutableStateOf(false) }
    val expanded = body.isNotEmpty() && (if (shared != null) store?.get(shared) == true else ownExpanded)
    fun toggle() {
        if (shared != null) store?.set(shared, !expanded) else ownExpanded = !expanded
    }
    // The label pulses while the thought is written, as the web's shimmer does.
    val pulse = if (streaming) {
        val transition = rememberInfiniteTransition(label = "thought-pulse")
        val a by transition.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "thought-alpha")
        a
    } else {
        1f
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.textPrimary.copy(alpha = 0.03f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = body.isNotEmpty()) { toggle() }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Psychology, null, tint = colors.textTertiary, modifier = Modifier.size(15.dp))
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(if (streaming) R.string.activity_thinking else R.string.activity_thought),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.alpha(pulse),
                )
                title?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            if (body.isNotEmpty()) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                MarkdownContent(body)
            }
        }
    }
}
