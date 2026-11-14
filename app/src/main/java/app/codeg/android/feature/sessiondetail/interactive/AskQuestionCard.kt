package app.codeg.android.feature.sessiondetail.interactive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.QuestionAnswer
import app.codeg.android.core.model.QuestionAnswerItem
import app.codeg.android.core.model.QuestionSpec

private const val OTHER_KEY = "__codeg_other__"

/** A multiple-choice / free-text question card. Port of iOS `AskQuestionCard`. */
@Composable
fun AskQuestionCard(
    questions: List<QuestionSpec>,
    onSubmit: (QuestionAnswer) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    val selections = remember(questions) { mutableStateMapOf<String, Set<String>>() }
    val otherText = remember(questions) { mutableStateMapOf<String, String>() }
    var submitting by remember(questions) { mutableStateOf(false) }

    val canSubmit = questions.all { isAnswered(it, selections[it.id].orEmpty(), otherText[it.id].orEmpty()) } &&
        !submitting

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.bgElevated.copy(alpha = 0.96f))
            .border(0.5.dp, colors.surfaceStroke, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Forum, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ask_needs_input_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text(stringResource(R.string.ask_needs_input_subtitle), fontSize = 12.sp, color = colors.textSecondary)
            }
        }

        Column(
            Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (q in questions) {
                QuestionBlock(
                    question = q,
                    showHeader = questions.size > 1,
                    selected = selections[q.id].orEmpty(),
                    otherText = otherText[q.id].orEmpty(),
                    onToggle = { label ->
                        val cur = selections[q.id].orEmpty()
                        selections[q.id] = if (q.multiSelect) {
                            if (label in cur) cur - label else cur + label
                        } else {
                            if (label in cur) emptySet() else setOf(label)
                        }
                    },
                    onOtherText = { otherText[q.id] = it },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSkip, enabled = !submitting) {
                Text(stringResource(R.string.ask_skip), color = colors.textSecondary)
            }
            Box(Modifier.weight(1f))
            Button(
                onClick = {
                    submitting = true
                    onSubmit(buildAnswer(questions, selections, otherText))
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.onAccent,
                    disabledContainerColor = colors.accent.copy(alpha = 0.4f),
                    disabledContentColor = colors.onAccent.copy(alpha = 0.8f),
                ),
            ) {
                if (submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.onAccent)
                } else {
                    Text(stringResource(R.string.ask_submit), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun QuestionBlock(
    question: QuestionSpec,
    showHeader: Boolean,
    selected: Set<String>,
    otherText: String,
    onToggle: (String) -> Unit,
    onOtherText: (String) -> Unit,
) {
    val colors = CodegTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (question.multiSelect) stringResource(R.string.ask_badge_multiple) else stringResource(R.string.ask_badge_single),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.textPrimary.copy(alpha = 0.06f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
            if (showHeader && question.header.isNotEmpty()) {
                Text(question.header, fontSize = 11.sp, color = colors.textTertiary)
            }
        }
        Text(question.question, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)

        for (option in question.options) {
            OptionRow(
                label = option.label,
                description = option.description,
                checked = option.label in selected,
                multi = question.multiSelect,
                onClick = { onToggle(option.label) },
            )
        }
        // "Other" free-text row — only where a typed answer is accepted. A question with no
        // options shows its input directly; one restricted to its options
        // (`allowOther == false`) shows none, since anything else cancels it server-side.
        if (question.acceptsFreeText && !question.freeTextOnly) {
            OptionRow(
                label = stringResource(R.string.ask_other),
                description = "",
                checked = OTHER_KEY in selected,
                multi = question.multiSelect,
                onClick = { onToggle(OTHER_KEY) },
            )
        }
        if (question.freeTextOnly || (question.acceptsFreeText && OTHER_KEY in selected)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.codeSurface)
                    .border(0.5.dp, colors.surfaceStroke, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (otherText.isEmpty()) Text(stringResource(R.string.ask_other_placeholder), color = colors.textTertiary, fontSize = 13.sp)
                BasicTextField(
                    value = otherText,
                    onValueChange = onOtherText,
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(colors.accent),
                    visualTransformation = if (question.isSecret) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = if (question.isSecret) {
                        KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                    } else {
                        KeyboardOptions.Default
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, description: String, checked: Boolean, multi: Boolean, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) colors.accent.copy(alpha = 0.12f) else colors.textPrimary.copy(alpha = 0.02f))
            .border(0.5.dp, if (checked) colors.accent.copy(alpha = 0.4f) else colors.hairline, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Decorative control; the whole row handles the toggle.
        if (multi) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.textTertiary),
            )
        } else {
            RadioButton(
                selected = checked,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.textTertiary),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = colors.textPrimary)
            if (description.isNotEmpty()) Text(description, fontSize = 11.sp, color = colors.textTertiary)
        }
    }
}

/** Whether the typed answer counts for [q]: it must be accepted, and either the question is
 *  free-text only or the user picked "Other". */
private fun typedAnswerCounts(q: QuestionSpec, selected: Set<String>): Boolean =
    q.acceptsFreeText && (q.freeTextOnly || OTHER_KEY in selected)

internal fun isAnswered(q: QuestionSpec, selected: Set<String>, otherText: String): Boolean =
    selected.any { it != OTHER_KEY } || (typedAnswerCounts(q, selected) && otherText.isNotBlank())

internal fun buildAnswer(
    questions: List<QuestionSpec>,
    selections: Map<String, Set<String>>,
    otherText: Map<String, String>,
): QuestionAnswer {
    val items = questions.map { q ->
        val sel = selections[q.id].orEmpty()
        val labels = sel.filter { it != OTHER_KEY }.toMutableList()
        if (typedAnswerCounts(q, sel)) otherText[q.id]?.takeIf { it.isNotBlank() }?.let { labels.add(it.trim()) }
        QuestionAnswerItem(questionId = q.id, labels = labels)
    }
    return QuestionAnswer(answers = items, declined = false)
}

/** The "Other" selection key, for tests. */
internal const val OTHER_SELECTION_KEY = OTHER_KEY
