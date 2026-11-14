package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionConfigSelectGroup
import app.codeg.android.core.model.SessionConfigSelectOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the web's `src/lib/model-config-groups.test.ts` (codeg 0.44). */
class ModelOptionGroupsTest {

    private fun opt(value: String, name: String = value, description: String? = null) =
        SessionConfigSelectOption(value, name, description)

    private fun modelOption(
        options: List<SessionConfigSelectOption>,
        id: String = "model",
        category: String? = null,
        groups: List<SessionConfigSelectGroup> = emptyList(),
    ) = SessionConfigOption(
        id = id,
        name = "Model",
        category = category,
        kind = SessionConfigKind(type = "select", currentValue = options.firstOrNull()?.value ?: "", options = options, groups = groups),
    )

    // isModelOption

    @Test
    fun `matches the model option by id`() {
        assertTrue(ModelOptionGroups.isModelOption(modelOption(listOf(opt("a")))))
    }

    @Test
    fun `matches by category when id differs`() {
        assertTrue(ModelOptionGroups.isModelOption(modelOption(listOf(opt("a")), id = "primary-model", category = "model")))
    }

    @Test
    fun `rejects the mode picker and other options`() {
        assertFalse(ModelOptionGroups.isModelOption(modelOption(listOf(opt("a")), id = "mode", category = "mode")))
        assertFalse(ModelOptionGroups.isModelOption(modelOption(listOf(opt("a")), id = "effort")))
    }

    // derive

    @Test
    fun `returns null for non-model options`() {
        assertNull(ModelOptionGroups.derive(modelOption(listOf(opt("anthropic/opus")), id = "mode", category = "mode")))
    }

    @Test
    fun `returns null when no value carries a slash`() {
        assertNull(ModelOptionGroups.derive(modelOption(listOf(opt("default", "Default"), opt("opus", "Opus"), opt("haiku", "Haiku")))))
    }

    @Test
    fun `respects server-provided groups verbatim`() {
        val option = modelOption(
            listOf(opt("anthropic/opus")),
            groups = listOf(SessionConfigSelectGroup("anthropic", "Anthropic", listOf(opt("anthropic/opus", "Opus")))),
        )
        assertNull(ModelOptionGroups.derive(option))
    }

    @Test
    fun `groups by the first slash and preserves first-seen order`() {
        val groups = ModelOptionGroups.derive(
            modelOption(listOf(opt("anthropic/claude-opus"), opt("openai/gpt-4o"), opt("anthropic/claude-sonnet"), opt("google/gemini-2.0"))),
        )!!
        assertEquals(listOf("anthropic", "openai", "google"), groups.map { it.name })
        val anthropic = groups.first { it.name == "anthropic" }
        // Labels strip the redundant `anthropic/` prefix (name repeated the value).
        assertEquals(listOf("claude-opus", "claude-sonnet"), anthropic.options.map { it.name })
        // Values are never rewritten.
        assertEquals(listOf("anthropic/claude-opus", "anthropic/claude-sonnet"), anthropic.options.map { it.value })
    }

    @Test
    fun `uses the shared display-name prefix as header and strips it from rows`() {
        val groups = ModelOptionGroups.derive(
            modelOption(
                listOf(
                    opt("opencode/big-pickle", "OpenCode Zen/Big Pickle"),
                    opt("opencode/claude-haiku", "OpenCode Zen/Claude Haiku"),
                    opt("anthropic/claude-opus", "anthropic/claude-opus"),
                ),
            ),
        )!!
        val zen = groups.first { it.key == "opencode" }
        assertEquals("OpenCode Zen", zen.name)
        assertEquals(listOf("Big Pickle", "Claude Haiku"), zen.options.map { it.name })
        assertEquals(listOf("opencode/big-pickle", "opencode/claude-haiku"), zen.options.map { it.value })
    }

    @Test
    fun `falls back to the value-id prefix as header when names don't share one`() {
        val groups = ModelOptionGroups.derive(
            modelOption(listOf(opt("anthropic/claude-opus", "Claude Opus"), opt("anthropic/claude-sonnet", "Claude Sonnet"), opt("openai/gpt-4o", "GPT-4o"))),
        )!!
        val anthropic = groups.first { it.key == "anthropic" }
        assertEquals("anthropic", anthropic.name)
        assertEquals(listOf("Claude Opus", "Claude Sonnet"), anthropic.options.map { it.name })
    }

    @Test
    fun `floats prefix-less values into a leading headerless bucket`() {
        val groups = ModelOptionGroups.derive(
            modelOption(listOf(opt("default", "Default"), opt("anthropic/opus", "anthropic/opus"), opt("openai/gpt", "openai/gpt"))),
        )!!
        assertNull(groups[0].name)
        assertEquals(listOf("Default"), groups[0].options.map { it.name })
        assertEquals(listOf("anthropic", "openai"), groups.drop(1).map { it.name })
    }

    @Test
    fun `groups a lone provider, stripping the prefix repeated on every row`() {
        val groups = ModelOptionGroups.derive(modelOption(listOf(opt("anthropic/opus"), opt("anthropic/sonnet"))))!!
        assertEquals(listOf("anthropic"), groups.map { it.name })
        assertEquals(listOf("opus", "sonnet"), groups[0].options.map { it.name })
    }

    @Test
    fun `strips the shared prefix for a single-provider OpenCode list`() {
        val groups = ModelOptionGroups.derive(
            modelOption(listOf(opt("opencode/big-pickle", "OpenCode Zen/Big Pickle"), opt("opencode/claude-haiku", "OpenCode Zen/Claude Haiku"))),
        )!!
        assertEquals(listOf("OpenCode Zen"), groups.map { it.name })
        assertEquals(listOf("Big Pickle", "Claude Haiku"), groups[0].options.map { it.name })
        assertEquals(listOf("opencode/big-pickle", "opencode/claude-haiku"), groups[0].options.map { it.value })
    }

    @Test
    fun `keeps a lone provider flat when its names carry no repeated prefix`() {
        assertNull(ModelOptionGroups.derive(modelOption(listOf(opt("anthropic/opus", "Opus"), opt("anthropic/sonnet", "Sonnet")))))
    }

    @Test
    fun `does not mistake a lone slashed display name for a provider prefix`() {
        val groups = ModelOptionGroups.derive(
            modelOption(listOf(opt("anthropic/claude-opus", "anthropic/claude-opus"), opt("meta/llama", "Big/Thing"))),
        )!!
        val meta = groups.first { it.key == "meta" }
        assertEquals("meta", meta.name)
        assertEquals(listOf("Big/Thing"), meta.options.map { it.name })
    }

    @Test
    fun `groups a lone provider once a prefix-less value floats beside it`() {
        val groups = ModelOptionGroups.derive(modelOption(listOf(opt("default", "Default"), opt("anthropic/opus"))))!!
        assertEquals(listOf(null, "anthropic"), groups.map { it.name })
    }

    @Test
    fun `groups multi-segment values under their first segment`() {
        val groups = ModelOptionGroups.derive(
            modelOption(listOf(opt("openrouter/anthropic/claude"), opt("openrouter/openai/gpt"), opt("ollama/llama3"))),
        )!!
        assertEquals(listOf("openrouter", "ollama"), groups.map { it.name })
        // Only the first `openrouter/` token is stripped; the sub-path remains.
        assertEquals(listOf("anthropic/claude", "openai/gpt"), groups.first { it.name == "openrouter" }.options.map { it.name })
    }

    @Test
    fun `does not strip a human label that does not repeat the prefix`() {
        val groups = ModelOptionGroups.derive(modelOption(listOf(opt("anthropic/claude-opus", "Claude Opus"), opt("openai/gpt-4o", "GPT-4o"))))!!
        assertEquals(listOf("Claude Opus", "GPT-4o"), groups.flatMap { g -> g.options.map { it.name } })
    }

    @Test
    fun `treats leading and trailing slashes as ungroupable`() {
        val groups = ModelOptionGroups.derive(
            modelOption(listOf(opt("/leading", "/leading"), opt("trailing/", "trailing/"), opt("anthropic/opus", "anthropic/opus"))),
        )!!
        assertNull(groups[0].name)
        assertEquals(listOf("/leading", "trailing/"), groups[0].options.map { it.value })
        assertEquals(listOf("anthropic"), groups.drop(1).map { it.name })
    }

    // listGroups

    @Test
    fun `uses the derived provider groups when applicable`() {
        val option = modelOption(listOf(opt("anthropic/opus", "anthropic/opus"), opt("openai/gpt-4o", "openai/gpt-4o")))
        assertEquals(listOf("anthropic", "openai"), ModelOptionGroups.listGroups(option).map { it.name })
    }

    @Test
    fun `preserves server-provided groups`() {
        val option = modelOption(
            emptyList(),
            groups = listOf(
                SessionConfigSelectGroup("fast", "Fast", listOf(opt("a", "A"), opt("b", "B"))),
                SessionConfigSelectGroup("smart", "Smart", listOf(opt("c", "C"))),
            ),
        )
        val groups = ModelOptionGroups.listGroups(option)
        assertEquals(listOf("Fast", "Smart"), groups.map { it.name })
        assertEquals(listOf("a", "b", "c"), groups.flatMap { g -> g.options.map { it.value } })
    }

    @Test
    fun `falls back to a single headerless group for a flat list`() {
        val groups = ModelOptionGroups.listGroups(modelOption(listOf(opt("a", "A"), opt("b", "B"), opt("c", "C"))))
        assertEquals(1, groups.size)
        assertNull(groups[0].name)
        assertEquals(listOf("a", "b", "c"), groups[0].options.map { it.value })
    }

    // filter

    private val sample = listOf(
        ModelOptionGroup("__ungrouped__", null, listOf(opt("default", "Default"))),
        ModelOptionGroup("anthropic", "anthropic", listOf(opt("anthropic/opus", "opus"), opt("anthropic/sonnet", "sonnet"))),
        ModelOptionGroup("openai", "openai", listOf(opt("openai/gpt-4o", "gpt-4o"))),
    )

    @Test
    fun `returns the groups unchanged for an empty query`() {
        assertSame(sample, ModelOptionGroups.filter(sample, "   "))
    }

    @Test
    fun `matches on the display name and drops empty groups`() {
        val result = ModelOptionGroups.filter(sample, "OPUS")
        assertEquals(listOf("anthropic"), result.map { it.key })
        assertEquals(listOf("anthropic/opus"), result[0].options.map { it.value })
    }

    @Test
    fun `matches on the value id even when the label was stripped`() {
        assertEquals(listOf("openai"), ModelOptionGroups.filter(sample, "openai").map { it.key })
    }

    @Test
    fun `returns an empty list when nothing matches`() {
        assertEquals(emptyList<ModelOptionGroup>(), ModelOptionGroups.filter(sample, "zzz"))
    }

    // flatten

    @Test
    fun `emits a header row per named group and one row per option`() {
        val rows = ModelOptionGroups.flatten(sample).map { r ->
            when (r) {
                is ModelOptionRow.Header -> "#${r.name}"
                is ModelOptionRow.Option -> r.option.value
            }
        }
        // The floating bucket (no name) contributes no header row.
        assertEquals(listOf("default", "#anthropic", "anthropic/opus", "anthropic/sonnet", "#openai", "openai/gpt-4o"), rows)
    }

    @Test
    fun `namespaces option keys by group so duplicate values never collide`() {
        val rows = ModelOptionGroups.flatten(
            listOf(ModelOptionGroup("a", "a", listOf(opt("x/m", "m"))), ModelOptionGroup("b", "b", listOf(opt("x/m", "m")))),
        )
        val keys = rows.filterIsInstance<ModelOptionRow.Option>().map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
