package app.codeg.android.feature.sessiondetail.composer

import app.codeg.android.core.model.AgentSkillItem
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.AvailableCommandInfo
import app.codeg.android.feature.sessiondetail.ComposeInsert

// The composer's inline triggers — `/` (and Codex's `$`) for the command and skill menu, `@`
// for the mention panel — ported from web `src/components/chat/message-input.tsx`
// (`detectSlashTrigger`), `composer/suggestion/mention-match.ts` and
// `composer/invocation-reference.ts`, by way of iOS's `ComposerTrigger.swift`.
//
// Offsets are UTF-16 code units, which is what a Kotlin String indexes and what the web's
// offsets and regexes measure — so no conversion is needed anywhere.

/**
 * What a JavaScript regex `\s` matches: ECMAScript WhiteSpace plus LineTerminator. Spelled out
 * because Kotlin's `isWhitespace` differs from it at the edges (U+0085, U+FEFF, U+00A0).
 */
internal object JsWhitespace {
    fun contains(c: Char): Boolean = when (c.code) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF,
        -> true
        in 0x2000..0x200A -> true
        else -> false
    }

    /** JS `String.prototype.trim`. */
    fun trim(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && contains(s[start])) start++
        while (end > start && contains(s[end - 1])) end--
        return s.substring(start, end)
    }
}

/** The prefix a disk skill or command is invoked by. */
enum class InvocationPrefix(val char: Char) {
    SLASH('/'),
    DOLLAR('$'),
    ;

    val text: String get() = char.toString()
}

/** Where a typed `@` may open the mention panel (web `canStartMentionAt`). */
internal object MentionStart {
    private val SPACELESS_SCRIPTS = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.BOPOMOFO,
    )

    /**
     * One code point after which a typed `@` may still open the panel: whitespace, the scripts
     * written without spaces between words (by `Script`, not `Script_Extensions`, which would
     * admit combining marks), the punctuation blocks they use (CJK Symbols and Punctuation,
     * CJK Compatibility Forms, Halfwidth/Fullwidth Forms), and four `Script=Common` marks CJK
     * words end on (· ゠ ・ ー). ASCII letters, digits and punctuation are absent on purpose:
     * `me@x.com` must not open a file picker.
     */
    fun isAllowedPrefix(codePoint: Int): Boolean {
        if (codePoint <= 0xFFFF && JsWhitespace.contains(codePoint.toChar())) return true
        when (codePoint) {
            in 0x3000..0x303F, in 0xFE30..0xFE4F, in 0xFF00..0xFFEF,
            0x00B7, 0x30A0, 0x30FB, 0x30FC,
            -> return true
        }
        if (codePoint <= 0x7F) return false
        val script = runCatching { Character.UnicodeScript.of(codePoint) }.getOrNull() ?: return false
        return script in SPACELESS_SCRIPTS
    }

    /**
     * May a mention start at [index] (the offset of the `@`) in [text]? True at the very start.
     * Steps back a whole code point, so a Han character beyond the BMP counts as one.
     */
    fun canStart(text: CharSequence, index: Int): Boolean {
        if (index <= 0) return true
        if (index > text.length) return false
        val unit = text[index - 1]
        val codePoint = if (unit.isLowSurrogate() && index >= 2 && text[index - 2].isHighSurrogate()) {
            Character.toCodePoint(text[index - 2], unit)
        } else {
            unit.code
        }
        return isAllowedPrefix(codePoint)
    }
}

/**
 * Web `invocation-reference.ts`: which agents get disk skills, under which trigger, and the
 * literal token a command runs as.
 */
internal object ComposerInvocations {
    /**
     * The trigger under which the menu lists an agent's skills read from disk, or null when the
     * agent advertises its skills as commands itself.
     *
     * - Codex: `$`, its own skill-mention syntax.
     * - Antigravity, OpenCode: `/`, beside their commands (their ACP servers advertise only
     *   commands, never a skill).
     * - Custom ACP agents: `/` — codeg cannot tell what an arbitrary server advertises, so the
     *   scan follows the skill directories the user declared for it.
     */
    fun diskSkillTrigger(agent: AgentType?): InvocationPrefix? = when {
        agent == null -> null
        agent == AgentType.CODEX -> InvocationPrefix.DOLLAR
        agent == AgentType.OPEN_CODE || agent == AgentType.ANTIGRAVITY || agent.isCustom -> InvocationPrefix.SLASH
        else -> null
    }

    /** The literal token an advertised command executes as ([ComposeInsert.invocationToken]). */
    fun commandInvocationToken(name: String): String = ComposeInsert.invocationToken(name)

    /** What the agent says goes after a command, or null when it advertises nothing there. */
    fun commandInputHint(command: AvailableCommandInfo): String? =
        command.inputHint?.let(JsWhitespace::trim)?.takeIf { it.isNotEmpty() }

    /**
     * The disk skills a menu offers under [trigger]. Under `/` they share the menu with the
     * agent's commands, and a skill whose `/<id>` is also a command is left out — sending that
     * token runs the command.
     */
    fun skillsForMenu(
        skills: List<AgentSkillItem>,
        commands: List<AvailableCommandInfo>,
        trigger: InvocationPrefix,
    ): List<AgentSkillItem> {
        if (trigger != InvocationPrefix.SLASH || commands.isEmpty()) return skills
        val tokens = commands.mapTo(HashSet()) { commandInvocationToken(it.name) }
        return skills.filterNot { "/${it.id}" in tokens }
    }

    /**
     * The names of the commands a menu marks as skills, or none. The marks count only in a list
     * that has a skill WITHOUT a path: claude-agent-acp marks skills in two passes and only the
     * second (after Claude Code's own skill list was read) marks them all, so an earlier list
     * would show some skills marked and others not.
     */
    fun skillMarkedCommands(commands: List<AvailableCommandInfo>): Set<String> {
        if (commands.none { it.skill && it.skillPath.isNullOrEmpty() }) return emptySet()
        return commands.filter { it.skill }.mapTo(LinkedHashSet()) { it.name }
    }
}

/**
 * The trigger the caret sits in, if any. [start] until [end] (exclusive) is the `/`·`$`·`@`
 * character through the caret — exactly what a selection replaces.
 */
sealed interface ComposerTrigger {
    val query: String
    val start: Int
    val end: Int

    data class Command(val prefix: InvocationPrefix, override val query: String, override val start: Int, override val end: Int) : ComposerTrigger

    data class Mention(override val query: String, override val start: Int, override val end: Int) : ComposerTrigger

    companion object {
        fun detect(text: String, caret: Int, agent: AgentType?): ComposerTrigger? =
            detect(text, caret, ComposerInvocations.diskSkillTrigger(agent))

        /**
         * Both triggers read the text before the caret. When both match (only possible across
         * a CJK boundary, e.g. `/文@x`), the one nearer the caret is the one being typed.
         */
        fun detect(text: String, caret: Int, skillTrigger: InvocationPrefix?): ComposerTrigger? {
            if (caret < 0 || caret > text.length) return null
            val command = detectCommand(text, caret, skillTrigger)
            val mention = detectMention(text, caret)
            return when {
                command != null && mention != null -> if (mention.start > command.start) mention else command
                else -> command ?: mention
            }
        }

        /**
         * Web `(^|\s)(\/)(\S*)$` — or `(^|\s)([/$])(\S*)$` when the agent's disk skills are
         * triggered by `$` — against the text before the caret: the run of non-whitespace
         * ending at the caret must open with the trigger.
         */
        fun detectCommand(text: String, caret: Int, skillTrigger: InvocationPrefix?): Command? {
            var start = caret
            while (start > 0 && !JsWhitespace.contains(text[start - 1])) start--
            if (start >= caret) return null
            val prefix = when {
                text[start] == '/' -> InvocationPrefix.SLASH
                skillTrigger == InvocationPrefix.DOLLAR && text[start] == '$' -> InvocationPrefix.DOLLAR
                else -> return null
            }
            return Command(prefix, text.substring(start + 1, caret), start, caret)
        }

        /**
         * Tiptap's suggestion matcher with `allowSpaces: false` plus the web's prefix rule: the
         * last `@` before the caret, no whitespace between it and the caret, and a character
         * before it that may start a mention.
         */
        fun detectMention(text: String, caret: Int): Mention? {
            var at = caret - 1
            while (at >= 0) {
                val c = text[at]
                if (c == '@') break
                if (JsWhitespace.contains(c)) return null
                at--
            }
            if (at < 0 || !MentionStart.canStart(text, at)) return null
            return Mention(text.substring(at + 1, caret), at, caret)
        }
    }
}

/** A replacement in the composer's text, plus where the caret goes afterwards. */
data class ComposerEdit(val start: Int, val end: Int, val replacement: String) {
    val caretAfter: Int get() = start + replacement.length

    /** Null when the range no longer fits the text (it changed underneath). */
    fun apply(text: String): String? {
        if (start < 0 || end > text.length || start > end) return null
        return text.substring(0, start) + replacement + text.substring(end)
    }
}
