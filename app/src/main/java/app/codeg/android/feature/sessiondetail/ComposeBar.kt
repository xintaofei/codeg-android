package app.codeg.android.feature.sessiondetail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.KeyboardTab
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.feature.sessiondetail.composer.ComposerKey
import app.codeg.android.feature.sessiondetail.composer.ComposerKeyOutcome
import app.codeg.android.feature.sessiondetail.composer.ComposerPick
import app.codeg.android.feature.sessiondetail.composer.ComposerSuggestionPanel
import app.codeg.android.feature.sessiondetail.composer.ComposerSuggestions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * The prompt composer pinned to the bottom of the session detail screen: a
 * multiline text field flanked by an insert ("+"), attach, and send / stop
 * action. The send button morphs between Send and Stop with a Material
 * scale-and-fade transition and an accent↔danger colour tween. All actions are
 * Material [FilledIconButton] / [FilledTonalIconButton]s, so they carry native
 * ripples and 40dp touch targets.
 *
 * While a turn is running the bar has a second job: on sessions that accept mid-turn
 * steering notes ([onSendNote]) the text field stays live and grows a note button beside
 * Stop. Stop keeps the trailing slot it has always had — the destructive action must not
 * slide under a finger that was already reaching for it.
 */
@Composable
fun ComposeBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isInFlight: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onPlus: (() -> Unit)? = null,
    onAttach: (() -> Unit)? = null,
    canSendOverride: Boolean = false,
    /** Send the draft to the running agent as a steering note. Null when this session
     *  can't take one, or when no turn is running — the affordance is absent, not disabled. */
    onSendNote: (() -> Unit)? = null,
    /** Whether a note lands in the running turn at once (native steering) or waits for the
     *  agent to check for it. Chooses the placeholder and the button's icon. */
    noteLandsInstantly: Boolean = false,
    /** A note submit is awaiting the server. */
    sendingNote: Boolean = false,
    /** Queue the draft to go out once the running turn ends. Null when no turn is running. */
    onQueue: (() -> Unit)? = null,
    /** The prompt the agent predicts comes next, offered in gray while the field is empty; a
     *  tap on the button beside it fills it in (nothing is sent). */
    suggestion: String? = null,
    /** The inline `/`·`$` menu and `@` panel this field feeds; null for a field without them. */
    suggestions: ComposerSuggestions? = null,
) {
    val colors = CodegTheme.colors
    // The field keeps its own selection (the menus read the caret); the text stays the
    // caller's. An outside change — a send clearing it, a restored draft — puts the caret at
    // the end.
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val value = if (field.text == text) field else TextFieldValue(text, TextRange(text.length))
    // Adopted as the field's own once drawn, so a draft cleared and then restored to the same
    // text never brings back the caret (or composition) it had before.
    LaunchedEffect(text) {
        if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
    }
    var focused by remember { mutableStateOf(false) }
    val panel = suggestions?.panel?.collectAsStateWithLifecycle()?.value
    if (suggestions != null) {
        LaunchedEffect(suggestions, value.text, value.selection, focused) {
            suggestions.update(value.text, value.selection.takeIf { it.collapsed }?.end, focused)
        }
    }
    val applyPick: (ComposerPick) -> Unit = { pick ->
        field = TextFieldValue(pick.text, TextRange(pick.caret))
        if (pick.text != text) onTextChange(pick.text)
    }
    // `sendingNote` blocks the ordinary Send too. The draft is deliberately still on screen
    // while a note is in flight, and the turn can finish underneath it — a native note is
    // allowed to complete after `turn_complete`. Without this the Stop would flip back to
    // Send with the note's own text sitting there, one tap from being delivered twice.
    val canSend = (text.isNotBlank() || canSendOverride) && !isInFlight && !sendingNote
    val canSendNote = onSendNote != null && text.isNotBlank() && !sendingNote
    // Same reason as `canSend` for holding it while a note is in flight: the queued copy
    // would be the note's own text, sent a second time once the turn ends.
    val canQueue = onQueue != null && (text.isNotBlank() || canSendOverride) && !sendingNote
    val sendContainer by animateColorAsState(
        targetValue = if (isInFlight) colors.danger else colors.accent,
        animationSpec = tween(220),
        label = "send-container",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        if (suggestions != null && panel != null) {
            ComposerSuggestionPanel(
                state = panel,
                onPick = { row -> suggestions.pick(row, value.text)?.let(applyPick) },
                onSelectTab = suggestions::selectTab,
            )
        }
        // A hairline setting the composer off from the timeline above it — the same
        // separator the bottom navigation bar uses, so every bottom-chrome surface is
        // delineated from its content the same way.
        HorizontalDivider(thickness = Dp.Hairline, color = colors.surfaceStroke)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bgElevated.copy(alpha = 0.92f))
                // The surface runs on under the gesture bar; the controls stop above it. With
                // the keyboard up the screen's imePadding has already taken this, so it adds
                // nothing then.
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (onPlus != null) {
                FilledTonalIconButton(
                    onClick = onPlus,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = colors.textPrimary.copy(alpha = 0.06f),
                        contentColor = colors.textSecondary,
                    ),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.compose_insert), modifier = Modifier.size(22.dp))
                }
            }
            if (onAttach != null) {
                FilledTonalIconButton(
                    onClick = onAttach,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = colors.textPrimary.copy(alpha = 0.06f),
                        contentColor = colors.textSecondary,
                    ),
                ) {
                    Icon(Icons.Rounded.Image, contentDescription = stringResource(R.string.compose_attach_image), modifier = Modifier.size(20.dp))
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    // The icon buttons lay out at their 48dp touch target with the 40dp disc
                    // centred in it; the same margin here keeps the field level with the discs
                    // (the row aligns bottoms so a growing draft keeps them at its foot).
                    .padding(vertical = 4.dp)
                    .heightIn(min = 40.dp)
                    .background(colors.codeSurface, RoundedCornerShape(20.dp))
                    .border(0.5.dp, colors.surfaceStroke, RoundedCornerShape(20.dp))
                    // One 24sp line plus this is exactly the buttons' 40dp, so a one-line field
                    // sits level with them; longer drafts grow upward from the shared baseline.
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                val offered = suggestion?.takeIf { text.isEmpty() && onSendNote == null && it.isNotBlank() }
                if (offered != null) {
                    Text(
                        offered,
                        color = colors.textTertiary,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (text.isEmpty()) {
                    Text(
                        // Mid-turn the field means something different, and the two note
                        // channels differ in when the agent sees it — say which before the
                        // user commits to typing, not after.
                        stringResource(
                            when {
                                onSendNote == null -> R.string.compose_message_placeholder
                                noteLandsInstantly -> R.string.feedback_placeholder_instant
                                else -> R.string.feedback_placeholder_pending
                            },
                        ),
                        color = colors.textTertiary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = { next ->
                        field = next
                        if (next.text != text) onTextChange(next.text)
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        // A hardware keyboard drives an open menu: arrows move, Enter/Tab pick,
                        // Escape closes. Soft keyboards send none of these as key events.
                        .onPreviewKeyEvent { event ->
                            val s = suggestions
                            val key = composerKey(event)
                            if (s == null || panel == null || key == null) return@onPreviewKeyEvent false
                            when (val outcome = s.handleKey(key, value.text, composing = value.composition != null)) {
                                ComposerKeyOutcome.Ignored -> false
                                ComposerKeyOutcome.Handled -> true
                                is ComposerKeyOutcome.Replace -> {
                                    applyPick(ComposerPick(outcome.text, outcome.caret))
                                    true
                                }
                            }
                        },
                )
            }
            // The web fills it in on Tab; a phone has none, so the fill is a button of its own.
            val fill = suggestion?.takeIf { text.isEmpty() && onSendNote == null && it.isNotBlank() }
            if (fill != null) {
                FilledTonalIconButton(
                    onClick = { onTextChange(fill) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = colors.textPrimary.copy(alpha = 0.06f),
                        contentColor = colors.textSecondary,
                    ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardTab,
                        contentDescription = stringResource(R.string.compose_use_suggestion),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (onSendNote != null) {
                val noteLabel = stringResource(R.string.feedback_send)
                FilledIconButton(
                    onClick = onSendNote,
                    enabled = canSendNote,
                    // The name lives on the button, not the icon: the icon is swapped for a
                    // progress spinner mid-submit, and a label that comes and goes with the
                    // child would leave the control unnamed exactly while it is busy.
                    modifier = Modifier.semantics { contentDescription = noteLabel },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colors.accent,
                        contentColor = colors.onAccent,
                        disabledContainerColor = colors.accent.copy(alpha = 0.4f),
                        disabledContentColor = colors.onAccent.copy(alpha = 0.7f),
                    ),
                ) {
                    if (sendingNote) {
                        CircularProgressIndicator(
                            color = colors.onAccent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Icon(
                            imageVector = if (noteLandsInstantly) Icons.Rounded.Bolt else Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (onQueue != null) {
                FilledTonalIconButton(
                    onClick = onQueue,
                    enabled = canQueue,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = colors.accent.copy(alpha = 0.16f),
                        contentColor = colors.accent,
                        disabledContainerColor = colors.textPrimary.copy(alpha = 0.06f),
                        disabledContentColor = colors.textTertiary,
                    ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.PlaylistAdd,
                        contentDescription = stringResource(R.string.compose_queue),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            FilledIconButton(
                onClick = { if (isInFlight) onStop() else onSend() },
                enabled = canSend || isInFlight,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = sendContainer,
                    contentColor = colors.onAccent,
                    disabledContainerColor = colors.accent.copy(alpha = 0.4f),
                    disabledContentColor = colors.onAccent.copy(alpha = 0.7f),
                ),
            ) {
                AnimatedContent(
                    targetState = isInFlight,
                    transitionSpec = {
                        (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.6f)) togetherWith
                            (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.6f))
                    },
                    label = "send-stop-icon",
                ) { inFlight ->
                    Icon(
                        imageVector = if (inFlight) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                        contentDescription = if (inFlight) stringResource(R.string.compose_stop) else stringResource(R.string.compose_send),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** The hardware key an open suggestion menu may take, or null for any other key. */
private fun composerKey(event: KeyEvent): ComposerKey? {
    if (event.type != KeyEventType.KeyDown) return null
    return when (event.key) {
        Key.DirectionUp -> ComposerKey.UP
        Key.DirectionDown -> ComposerKey.DOWN
        // Shift+Enter stays the field's newline.
        Key.Enter, Key.NumPadEnter -> if (event.isShiftPressed) null else ComposerKey.ENTER
        Key.Tab -> if (event.isShiftPressed) ComposerKey.SHIFT_TAB else ComposerKey.TAB
        Key.Escape -> ComposerKey.ESCAPE
        else -> null
    }
}
