package app.codeg.android.feature.sessiondetail.rendering

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodeBlock
import app.codeg.android.core.designsystem.component.LivePulse
import app.codeg.android.core.designsystem.diff.DiffView
import app.codeg.android.core.designsystem.diff.UnifiedDiff
import app.codeg.android.core.designsystem.markdown.MarkdownContent
import app.codeg.android.core.designsystem.markdown.SingleBlockView
import app.codeg.android.core.designsystem.markdown.parseMarkdownBlocks
import app.codeg.android.core.designsystem.theme.CodegTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val SuccessGreen = Color(0xFF85D18F)

/** A collapsible card for a single tool call. Port of iOS `ToolCallCard`. */
@Composable
fun ToolCallCard(vm: ToolCallVM, modifier: Modifier = Modifier, nested: Boolean = false) {
    val colors = CodegTheme.colors
    val autoExpand = vm.state == ToolCallState.RUNNING ||
        vm.state == ToolCallState.ERROR ||
        (vm.diffFiles != null && (vm.diffFiles.sumOf { f -> f.hunks.sumOf { it.rows.size } } <= 40))
    var expanded by remember(vm.id) { mutableStateOf(autoExpand) }
    val hasBody = vm.diffFiles != null || !vm.input.isNullOrBlank() || vm.hasOutput

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (nested) Color.Transparent else colors.bgElevated.copy(alpha = 0.5f))
            .border(0.5.dp, colors.hairline, RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = hasBody) { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolStateIcon(vm)
            Text(
                text = vm.displayTitle,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            vm.diffFiles?.let { files ->
                val adds = files.sumOf { it.additions }
                val dels = files.sumOf { it.deletions }
                if (adds > 0) Text("+$adds", color = SuccessGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                if (dels > 0) Text("−$dels", color = colors.danger, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            if (hasBody) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded && hasBody,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToolBody(vm)
            }
        }
    }
}

@Composable
private fun ToolBody(vm: ToolCallVM) {
    val diff = vm.diffFiles
    if (diff != null) {
        DiffView(diff)
        return
    }
    // Input — a per-tool body for a plan document / shell / todos, else a structured
    // label→value field list. The field list is the fix for delegate_to_agent and other
    // MCP / generic tools, which previously dumped their whole argument object as a raw
    // JSON block.
    val input = vm.input
    if (!input.isNullOrBlank()) {
        // Content-driven, but only for a plan-mode transition: `ExitPlanMode` and a
        // `switch_mode` that proposes one carry freeform plan markdown, while another
        // tool's `plan` argument is just an argument.
        val plan = if (vm.isPlanMode) planMarkdown(input) else null
        when {
            plan != null -> PlanDocumentBody(plan, vm.id)
            CodexCodeMode.isScriptName(vm.rawName) -> CodexScriptBody(input)
            vm.bucket == ToolKindBucket.EXECUTE -> BashInputBody(input)
            vm.bucket == ToolKindBucket.TODO -> TodoInputBody(input)
            else -> StructuredJsonView(input)
        }
    }
    // Output.
    if (vm.hasOutput) ToolOutputSection(vm)
}

/**
 * The plan document a plan-mode transition carries, or null when the call is a
 * bare mode signal. Mirrors the web `extractPlanMarkdown`, including the nested
 * `rawInput` / `raw_input` wrapper some hosts add.
 */
internal fun planMarkdown(input: String): String? {
    val obj = ToolDerive.parseJson(input) ?: return null
    fun read(from: JsonObject): String? =
        (from.str("plan") ?: from.str("Plan"))?.takeIf { it.isNotBlank() }
    read(obj)?.let { return it }
    for (key in listOf("rawInput", "raw_input")) {
        (obj[key] as? JsonObject)?.let { nested -> read(nested)?.let { return it } }
    }
    return null
}

/**
 * The prefix of [markdown] a collapsed plan shows: at most [lineLimit] source
 * lines AND [charLimit] characters, whichever runs out first. The whole string
 * when it already fits, which is how the caller knows there is nothing to
 * expand.
 *
 * Both budgets are needed. Lines alone miss a plan written as one long
 * paragraph — a single source line that wraps to a screenful on a phone, so no
 * toggle ever appears and the plan buries the turn anyway, which is the very
 * thing the clip exists to stop. Characters alone would cut a short bulleted
 * plan mid-word, so the character budget still backs up to the last line break
 * it can find.
 */
internal fun clipPlanSource(markdown: String, lineLimit: Int = 24, charLimit: Int = 1500): String {
    var end = 0
    var lines = 0
    while (lines < lineLimit) {
        val next = markdown.indexOf('\n', end)
        if (next < 0) {
            end = markdown.length
            break
        }
        end = next + 1
        lines += 1
    }
    var cut = minOf(end, markdown.length)
    if (cut > charLimit) {
        val lastBreak = markdown.lastIndexOf('\n', charLimit)
        cut = if (lastBreak > 0) lastBreak else charLimit
    }
    // A character budget counts UTF-16 units, and an emoji is two of them:
    // landing between the pair leaves a lone surrogate, which renders as the
    // replacement glyph. Keep the whole code point out.
    if (cut in 1 until markdown.length && markdown[cut].isLowSurrogate() && markdown[cut - 1].isHighSurrogate()) {
        cut -= 1
    }
    return markdown.substring(0, cut).trimEnd()
}

/**
 * A plan document rendered as Markdown rather than the monospace argument blob
 * it used to be, clipped by [clipPlanSource] so a long plan cannot bury the
 * rest of the turn — the same show-more affordance [FileBodyView] uses for a
 * long file, and clipped the same way, on the source.
 *
 * Block-granular clipping was tried and does not work: it cannot cut INSIDE a
 * block, so one 60-line paragraph is one block and renders whole with no
 * toggle. Slicing the source can leave a fence unterminated, which
 * [parseMarkdownBlocks] already handles by running the block to the end.
 */
@Composable
private fun PlanDocumentBody(markdown: String, id: String) {
    val colors = CodegTheme.colors
    // Trimmed first: trailing blank lines render as nothing, so a plan padded
    // past the limit by them would offer a toggle that expands to the same text.
    val body = remember(markdown) { markdown.trimEnd() }
    val clipped = remember(body) { clipPlanSource(body) }
    // Keyed on the call, not the text: a plan still streaming grows on every
    // flush, and keying the state on its content re-collapses it under the
    // reader each time.
    var expanded by remember(id) { mutableStateOf(false) }
    val isLong = clipped.length < body.length
    val source = if (isLong && !expanded) clipped else body
    val blocks = remember(source) { parseMarkdownBlocks(source) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(0.5.dp, colors.hairline, RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.Checklist, null, tint = colors.textTertiary, modifier = Modifier.size(13.dp))
            Text(
                stringResource(R.string.plan_document_title),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textTertiary,
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (block in blocks) SingleBlockView(block)
        }
        if (isLong) {
            HorizontalDivider(thickness = 0.5.dp, color = colors.hairline)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        if (expanded) R.string.plan_document_show_less else R.string.plan_document_show_more,
                    ),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary,
                )
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/** Shell input: the command as a bash block. A JSON payload without a `command`
 *  field falls back to the structured field list; a bare command string (Codex
 *  `exec_command` persists the raw command, not JSON) shows as bash. */
/**
 * Whether a search result is an EMPTY one rather than a failure.
 *
 * codex derives tool status from the exit code, so a grep that selected nothing
 * arrives as a FAILURE carrying an empty envelope. That exact shape — grep,
 * exit 1, no output — reads as "no matches". Exit >= 2 is a real grep failure
 * and keeps its error rendering, as does a list-files action resolved to glob,
 * for which any non-zero exit is genuine; its empty SUCCESS, though, means the
 * same thing an empty search does.
 *
 * Only codex sends this envelope, and inside that gate [rawName] is exactly
 * what `CodexCommandAction.resolve` wrote — so comparing the literal is
 * comparing against its own output, and no other agent's `Grep` reaches here.
 */
internal fun isEmptySearchResult(
    rawName: String,
    isError: Boolean,
    envelope: CodexCommandAction.Envelope?,
): Boolean {
    if (envelope == null || envelope.output.isNotBlank()) return false
    return if (isError) rawName == "grep" && envelope.exitCode == 1L else envelope.exitCode == 0L
}

/**
 * A code-mode script's payload is a JavaScript program, not a shell line.
 * Dispatched on the `codex_script` identity rather than on the payload shape:
 * `{"source": …}` is an ordinary argument name, and any other execute tool
 * using it would render as JavaScript with its remaining arguments dropped.
 */
@Composable
private fun CodexScriptBody(input: String) {
    val script = CodexCodeMode.parseScript(ToolDerive.effectiveArgs(ToolDerive.parseJson(input)))
    if (script != null) CodeBlock(code = script.source, language = "javascript")
    else StructuredJsonView(input)
}

@Composable
private fun BashInputBody(input: String) {
    val parsed = ToolDerive.parseJson(input)
    val args = ToolDerive.effectiveArgs(parsed)
    val cmd = args?.str("command") ?: args?.str("cmd") ?: args?.str("script")
    when {
        cmd != null -> CodeBlock(code = cmd, language = "bash")
        parsed == null -> CodeBlock(code = input, language = "bash")
        else -> StructuredJsonView(input)
    }
}

/** Todo-write input rendered as an evolving checklist (status icon + strikethrough
 *  on completed items). Falls back to the field list when there are no todos. */
@Composable
private fun TodoInputBody(input: String) {
    val colors = CodegTheme.colors
    val args = ToolDerive.effectiveArgs(ToolDerive.parseJson(input))
    val todos = (args?.get("todos") as? JsonArray) ?: (args?.get("todoList") as? JsonArray)
    if (todos.isNullOrEmpty()) {
        StructuredJsonView(input)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        todos.forEach { el ->
            val todo = el as? JsonObject ?: return@forEach
            val status = todo.str("status") ?: "pending"
            val text = todo.str("content") ?: todo.str("title") ?: return@forEach
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    when (status) {
                        "completed" -> Icons.Rounded.CheckCircle
                        "in_progress" -> Icons.Rounded.Adjust
                        else -> Icons.Rounded.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = when (status) {
                        "completed" -> SuccessGreen
                        "in_progress" -> colors.accent
                        else -> colors.textTertiary
                    },
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text,
                    fontSize = 12.sp,
                    color = if (status == "completed") colors.textTertiary else colors.textSecondary,
                    textDecoration = if (status == "completed") TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

/** Tool output: a line-numbered Read file, a labeled error, a cleaned command
 *  console (JSON envelope unwrapped + CLI metadata stripped), or classified
 *  json / diff / markdown / log. */
@Composable
private fun ToolOutputSection(vm: ToolCallVM) {
    // Codex wraps every MCP result in `{result: <CallToolResult>, error: null}` on its
    // live wire; unwrap to the text the tool actually returned before classifying, or
    // the card renders the envelope's raw JSON.
    val out = ToolOutputFormat.mcpResultText(vm.trimmedOutput) ?: vm.trimmedOutput
    if (vm.bucket == ToolKindBucket.SEARCH) {
        SearchOutputSection(vm, out)
        return
    }
    if (vm.bucket == ToolKindBucket.READ && !vm.isError) {
        val file = ReadOutputFormat.parse(out)
        if (file != null && file.content.isNotBlank()) {
            FileBodyView(file)
            return
        }
    }
    when {
        vm.isError -> ToolErrorOutput(out)
        vm.isCommand -> {
            val clean = ToolOutputFormat.cleanCommandOutput(out)
            if (clean.isNotEmpty()) CodeBlock(code = clean, language = "console")
        }
        else -> when (ToolOutputFormat.classify(out)) {
            ToolOutputFormat.Kind.JSON -> CodeBlock(code = prettyPrintJsonString(out) ?: out, language = "json")
            ToolOutputFormat.Kind.DIFF ->
                if (UnifiedDiff.looksLikeDiff(out)) {
                    UnifiedDiff.parse(out)?.let { DiffView(it) } ?: CodeBlock(code = out, language = "diff")
                } else {
                    CodeBlock(code = out, language = "diff")
                }
            ToolOutputFormat.Kind.MARKDOWN -> MarkdownContent(out)
            ToolOutputFormat.Kind.LOG -> CodeBlock(code = out, language = "output")
        }
    }
}

/**
 * A search tool's output, grouped per file when it fits one of the two shapes
 * [SearchOutput] recognises and shown verbatim when it doesn't.
 *
 * Unwrapping is codex's `{formatted_output, exit_code}` envelope and nothing
 * else — no cleanup pass runs over it. `formatted_output` is the process's raw
 * output, and a matched line can begin with any of the words the command and
 * read paths treat as their own framing (`grep 'Wall time:' server.log`,
 * `grep '^Output:' log`), so every one of those steps is lossy on a real hit.
 */
@Composable
private fun SearchOutputSection(vm: ToolCallVM, out: String) {
    val envelope = CodexCommandAction.parseEnvelope(out)
    // NOT trimmed: `formatted_output` is the process's raw stdout, and a
    // single-file grep prints matches with no `<path>:` prefix, so a matched
    // line's own leading whitespace is content. Only the emptiness TEST trims.
    val body = envelope?.output ?: out

    if (isEmptySearchResult(vm.rawName, vm.isError, envelope)) {
        Text(
            stringResource(R.string.search_results_none),
            fontSize = 12.sp,
            color = CodegTheme.colors.textTertiary,
        )
        return
    }
    if (vm.isError) {
        ToolErrorOutput(body)
        return
    }
    if (body.isBlank()) return

    val parsed = remember(body) { SearchOutput.parse(body) }
    if (parsed != null) {
        // Same key list the search title reads, so the highlighted term is the
        // one the card is named after whichever agent's tool this is.
        val args = ToolDerive.effectiveArgs(ToolDerive.parseJson(vm.input))
        val query = listOf("pattern", "query", "regex", "q").firstNotNullOfOrNull { args?.str(it) }
        SearchResultsView(parsed, query, body)
        return
    }
    CodeBlock(code = body, language = "output")
}

@Composable
private fun ToolStateIcon(vm: ToolCallVM) {
    val colors = CodegTheme.colors
    when (vm.state) {
        ToolCallState.RUNNING, ToolCallState.INPUT_STREAMING ->
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.accent)
        ToolCallState.DONE -> Icon(bucketIcon(vm.bucket), null, tint = colors.textSecondary, modifier = Modifier.size(15.dp))
        ToolCallState.ERROR -> Icon(Icons.Rounded.ErrorOutline, null, tint = colors.danger, modifier = Modifier.size(15.dp))
    }
}

/** A summary card for 2+ consecutive tool calls. Port of iOS `ToolGroupCard`. */
@Composable
fun ToolGroupCard(items: List<ToolCallVM>, modifier: Modifier = Modifier, streaming: Boolean = false) {
    val colors = CodegTheme.colors
    // Key on the stable first-tool id, not the items' value: live grouped tools rebuild
    // their VMs every flush, and remember(items) would reset the expand state each time.
    var expanded by remember(items.firstOrNull()?.id) { mutableStateOf(false) }
    val errorCount = items.count { it.state == ToolCallState.ERROR }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.bgElevated.copy(alpha = 0.5f))
            .border(0.5.dp, colors.hairline, RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Layers, null, tint = colors.accent, modifier = Modifier.size(15.dp))
            Text(
                text = groupSummary(LocalContext.current, items),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (streaming) {
                LivePulse(dotSize = 7.dp)
            } else if (errorCount > 0) {
                Text(stringResource(R.string.toolcall_failed_count, errorCount), color = colors.danger, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (item in items) ToolCallCard(item, nested = true)
            }
        }
    }
}

/** Bucket → glyph (shared by the tool card and the timeline marker). */
fun bucketIcon(bucket: ToolKindBucket): ImageVector = when (bucket) {
    ToolKindBucket.READ -> Icons.Rounded.Description
    ToolKindBucket.EDIT -> Icons.Rounded.Edit
    ToolKindBucket.SEARCH -> Icons.Rounded.Search
    ToolKindBucket.EXECUTE -> Icons.Rounded.Terminal
    ToolKindBucket.WEB -> Icons.Rounded.Language
    ToolKindBucket.TODO -> Icons.Rounded.Checklist
    ToolKindBucket.TASK -> Icons.Rounded.Groups
    ToolKindBucket.OTHER -> Icons.Rounded.Build
}

/** "Read 3 files · Edited 2 files" — counts per bucket, fixed iOS order. Localized via plurals. */
private fun groupSummary(context: Context, items: List<ToolCallVM>): String {
    val counts = items.groupingBy { it.bucket }.eachCount()
    val order = listOf(
        ToolKindBucket.READ, ToolKindBucket.EDIT, ToolKindBucket.SEARCH, ToolKindBucket.EXECUTE,
        ToolKindBucket.WEB, ToolKindBucket.TODO, ToolKindBucket.TASK, ToolKindBucket.OTHER,
    )
    val res = context.resources
    val phrases = order.mapNotNull { b ->
        val c = counts[b] ?: return@mapNotNull null
        if (c == 0) return@mapNotNull null
        when (b) {
            ToolKindBucket.READ -> res.getQuantityString(R.plurals.tool_summary_read, c, c)
            ToolKindBucket.EDIT -> res.getQuantityString(R.plurals.tool_summary_edit, c, c)
            ToolKindBucket.SEARCH -> res.getQuantityString(R.plurals.tool_summary_search, c, c)
            ToolKindBucket.EXECUTE -> res.getQuantityString(R.plurals.tool_summary_execute, c, c)
            ToolKindBucket.WEB -> res.getQuantityString(R.plurals.tool_summary_web, c, c)
            ToolKindBucket.TODO -> context.getString(R.string.tool_summary_todos)
            ToolKindBucket.TASK -> res.getQuantityString(R.plurals.tool_summary_task, c, c)
            ToolKindBucket.OTHER -> res.getQuantityString(R.plurals.tool_summary_tools, c, c)
        }
    }
    return if (phrases.isEmpty()) res.getQuantityString(R.plurals.tool_summary_tools, items.size, items.size)
    else phrases.joinToString(" · ")
}

private fun JsonObject.str(key: String): String? {
    val prim = this[key] as? JsonPrimitive ?: return null
    return if (prim.isString) prim.content else null
}
