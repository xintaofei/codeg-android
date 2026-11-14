package app.codeg.android.feature.sessiondetail.composer

import app.codeg.android.core.model.AgentSkillItem
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.AvailableCommandInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Web `suggestion/mention-match.test.ts` (`canStartMentionAt`), ported case for case (via iOS). */
class MentionStartTest {
    /** `@` appended to [prefix], asked about at the `@`'s own offset. */
    private fun afterPrefix(prefix: String): Boolean = MentionStart.canStart("$prefix@app", prefix.length)

    @Test
    fun `allows the start and any whitespace`() {
        assertTrue(MentionStart.canStart("@app", 0))
        for (prefix in listOf(" ", "\n", "\t", " ", "　")) assertTrue("prefix ${prefix.map { it.code }}", afterPrefix(prefix))
    }

    @Test
    fun `allows the scripts written without spaces`() {
        for (prefix in listOf("请看", "汇", "テスト", "ん", "한글", "ㄅ", "你好，", "结束。", "「引用」", "（括号）", "标题：")) {
            assertTrue("prefix $prefix", afterPrefix(prefix))
        }
    }

    @Test
    fun `allows the common-script marks CJK words end on`() {
        assertTrue(afterPrefix("ユーザー"))
        assertTrue(afterPrefix("サーバー"))
        assertTrue(afterPrefix("ヨミ・カナ"))
        assertTrue(afterPrefix("ゲーム゠センター"))
        assertTrue(afterPrefix("玛丽·居里"))
    }

    @Test
    fun `a combining mark doesn't carry a Latin local part past the guard`() {
        assertFalse(afterPrefix("ạ"))
        assertFalse(afterPrefix("o̅"))
        assertFalse(afterPrefix("mẹ"))
    }

    @Test
    fun `allows a Han character from beyond the BMP`() {
        assertTrue(afterPrefix("𠀋"))
        assertTrue(afterPrefix("先祖𠀋"))
    }

    @Test
    fun `keeps blocking the tail of an email's local part`() {
        for (prefix in listOf("me", "Z", "foo9", "a.b", "a_b", "a-b", "a+b", "100%", "(", "[")) {
            assertFalse("prefix $prefix", afterPrefix(prefix))
        }
    }
}

/** The caret-scoped trigger detection (web `detectSlashTrigger` + Tiptap's `findMentionMatch`). */
class ComposerTriggerTest {
    private fun detect(text: String, caret: Int = text.length, agent: AgentType = AgentType.CLAUDE_CODE) =
        ComposerTrigger.detect(text, caret, agent)

    private fun command(query: String, start: Int, end: Int, prefix: InvocationPrefix = InvocationPrefix.SLASH) =
        ComposerTrigger.Command(prefix, query, start, end)

    @Test
    fun `slash opens at the start or after whitespace`() {
        assertEquals(command("", 0, 1), detect("/"))
        assertEquals(command("re", 0, 3), detect("/re"))
        assertEquals(command("rev", 7, 11), detect("please /rev"))
        assertEquals(command("co", 3, 6), detect("hi\n/co"))
    }

    @Test
    fun `slash inside a word does not open`() {
        assertNull(detect("a/b"))
        assertNull(detect("src/main"))
        assertNull("a space after the token ends it", detect("/re "))
    }

    @Test
    fun `only the text before the caret counts`() {
        assertEquals(command("rev", 0, 4), detect("/review the code", caret = 4))
        assertEquals(command("a/b", 2, 6), detect("x /a/b", caret = 6))
        assertNull("a caret past the text is no caret", detect("/re", caret = 99))
    }

    @Test
    fun `dollar opens only for the agent whose skills it triggers`() {
        assertEquals(command("dep", 0, 4, InvocationPrefix.DOLLAR), detect("\$dep", agent = AgentType.CODEX))
        assertNull(detect("\$dep", agent = AgentType.CLAUDE_CODE))
        assertNull("OpenCode's disk skills ride `/`", detect("\$dep", agent = AgentType.OPEN_CODE))
        assertEquals(command("x", 0, 2), detect("/x", agent = AgentType.CODEX))
    }

    @Test
    fun `at opens the mention panel`() {
        assertEquals(ComposerTrigger.Mention("", 0, 1), detect("@"))
        assertEquals(ComposerTrigger.Mention("src/ma", 4, 11), detect("see @src/ma"))
        assertEquals(ComposerTrigger.Mention("文件", 2, 5), detect("请看@文件"))
        assertEquals(ComposerTrigger.Mention("a", 3, 5), detect("结束。@a"))
    }

    @Test
    fun `an email address never opens it`() {
        assertNull(detect("me@x.com"))
        assertNull(detect("mail me@x"))
        assertNull("the last `@` follows a letter", detect("@a@b"))
    }

    @Test
    fun `a space ends the mention query`() {
        assertNull(detect("@foo bar"))
        assertNull(detect("@foo "))
    }

    @Test
    fun `the trigger nearer the caret wins`() {
        assertEquals(ComposerTrigger.Mention("x", 2, 4), detect("/文@x"))
        assertEquals(ComposerTrigger.Mention("/x", 0, 3), detect("@/x"))
    }
}

/** [ComposerEdit] replaces UTF-16 ranges. */
class ComposerEditTest {
    @Test
    fun `replaces the range and reports the caret`() {
        val edit = ComposerEdit(3, 6, "/review ")
        assertEquals("hi /review  there", edit.apply("hi /re there"))
        assertEquals(11, edit.caretAfter)
    }

    @Test
    fun `refuses a range that no longer fits`() {
        assertNull(ComposerEdit(2, 9, "x").apply("abc"))
    }
}

/** Web `composer/invocation-reference.test.ts`: the token rule, which agents get disk skills under
 *  which trigger, and the `/` menu's skill dedup. */
class ComposerInvocationsTest {
    private fun cmd(name: String, hint: String? = null, skill: Boolean = false, path: String? = null) =
        AvailableCommandInfo(name, "$name command", hint, skill, path)

    private fun skill(id: String, name: String) =
        AgentSkillItem(id = id, name = name, scope = "project", layout = "markdown_file", path = "/skills/$id.md", description = "desc")

    @Test
    fun `a command runs as slash name and a codex skill as its bare dollar name`() {
        assertEquals("/review", ComposerInvocations.commandInvocationToken("review"))
        assertEquals("\$deploy", ComposerInvocations.commandInvocationToken("\$deploy"))
    }

    @Test
    fun `disk skills follow the agent`() {
        assertEquals(InvocationPrefix.DOLLAR, ComposerInvocations.diskSkillTrigger(AgentType.CODEX))
        assertEquals(InvocationPrefix.SLASH, ComposerInvocations.diskSkillTrigger(AgentType.ANTIGRAVITY))
        assertEquals(InvocationPrefix.SLASH, ComposerInvocations.diskSkillTrigger(AgentType.OPEN_CODE))
        assertEquals(InvocationPrefix.SLASH, ComposerInvocations.diskSkillTrigger(AgentType.fromWire("custom:antigravity-b")))
        assertEquals(InvocationPrefix.SLASH, ComposerInvocations.diskSkillTrigger(AgentType.fromWire("custom:my-agent")))
        for (agent in listOf(AgentType.CLAUDE_CODE, AgentType.KIMI_CODE, AgentType.GEMINI)) {
            assertNull(ComposerInvocations.diskSkillTrigger(agent))
        }
        assertNull(ComposerInvocations.diskSkillTrigger(null))
    }

    @Test
    fun `a slash skill an advertised command shadows is left out`() {
        val skills = listOf(skill("plan", "Plan"), skill("ship", "Ship"))
        assertEquals(
            listOf("ship"),
            ComposerInvocations.skillsForMenu(skills, listOf(cmd("plan"), cmd("usage")), InvocationPrefix.SLASH).map { it.id },
        )
    }

    @Test
    fun `every dollar skill is kept`() {
        val skills = listOf(skill("plan", "Plan"), skill("ship", "Ship"))
        assertEquals(skills, ComposerInvocations.skillsForMenu(skills, listOf(cmd("plan"), cmd("\$ship")), InvocationPrefix.DOLLAR))
    }

    @Test
    fun `every skill is kept while there are no commands`() {
        val skills = listOf(skill("plan", "Plan"))
        assertEquals(skills, ComposerInvocations.skillsForMenu(skills, emptyList(), InvocationPrefix.SLASH))
    }

    @Test
    fun `the input hint is trimmed and blank means none`() {
        assertEquals("branch name", ComposerInvocations.commandInputHint(cmd("review-branch", hint = " branch name ")))
        assertNull(ComposerInvocations.commandInputHint(cmd("compact")))
        assertNull(ComposerInvocations.commandInputHint(cmd("compact", hint = "  ")))
    }

    @Test
    fun `skill marks count only in a list that has a pathless skill`() {
        val userSkill = cmd("user-skill", skill = true, path = "/home/u/.claude/skills/user-skill/SKILL.md")
        val bundled = cmd("simplify", skill = true)
        val compact = cmd("compact")
        assertTrue(
            "the first pass marks only the skills with a SKILL.md",
            ComposerInvocations.skillMarkedCommands(listOf(userSkill, compact)).isEmpty(),
        )
        assertEquals(setOf("user-skill", "simplify"), ComposerInvocations.skillMarkedCommands(listOf(userSkill, bundled, compact)))
    }
}
