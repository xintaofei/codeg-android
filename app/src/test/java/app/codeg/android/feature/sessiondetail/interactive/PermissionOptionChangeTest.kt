package app.codeg.android.feature.sessiondetail.interactive

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission card is a security decision surface: what it renders is what
 * the user believes they are granting. Port of the web
 * `permission-request.test.ts`.
 */
class PermissionOptionChangeTest {

    private fun changes(changesJson: String) =
        parsePermissionOptionChanges(
            Json.parseToJsonElement("""{"permission":{"version":1,"changes":$changesJson}}"""),
        )

    @Test
    fun `keeps the agent's description and normalizes every lifetime shape`() {
        // The six shapes the two adapters emit. claude-agent-acp >=0.64.1 spans
        // all of them; codex-acp only ever sends {scope: "session"}.
        val parsed = changes(
            """[
              {"description":"session","lifetime":{"scope":"session"}},
              {"description":"process","lifetime":{"scope":"process","storage":"cli_argument"}},
              {"description":"user","lifetime":{"scope":"persistent","storage":"user"}},
              {"description":"project","lifetime":{"scope":"persistent","storage":"project"}},
              {"description":"local","lifetime":{"scope":"persistent","storage":"project_local"}},
              {"description":"future","lifetime":{"scope":"persistent","storage":"enterprise_policy"}}
            ]""",
        )

        assertEquals(
            listOf(
                PermissionOptionChange("session", PermissionChangeScope.SESSION),
                PermissionOptionChange("process", PermissionChangeScope.PROCESS),
                PermissionOptionChange("user", PermissionChangeScope.USER),
                PermissionOptionChange("project", PermissionChangeScope.PROJECT),
                PermissionOptionChange("local", PermissionChangeScope.PROJECT_LOCAL),
                // Persistent with a storage this build has never seen: the
                // destination is unknown but "outlives the session" must still
                // reach the user.
                PermissionOptionChange("future", PermissionChangeScope.PERSISTENT),
            ),
            parsed,
        )
    }

    @Test
    fun `surrounding whitespace does not downgrade a scope`() {
        // The web reference trims every string field it picks. Untrimmed, a
        // padded `storage` would fall through to PERSISTENT and label a project
        // write as the vaguer "Saved permanently".
        val parsed = changes(
            """[
              {"description":" padded ","lifetime":{"scope":" session "}},
              {"description":"b","lifetime":{"scope":"persistent","storage":" project "}}
            ]""",
        )

        assertEquals(
            listOf(
                PermissionOptionChange("padded", PermissionChangeScope.SESSION),
                PermissionOptionChange("b", PermissionChangeScope.PROJECT),
            ),
            parsed,
        )
    }

    @Test
    fun `reports no scope when the lifetime is absent, unknown, or malformed`() {
        val parsed = changes(
            """[
              {"description":"no lifetime at all"},
              {"description":"explicitly unknown","lifetime":{"scope":"unknown"}},
              {"description":"scope from the future","lifetime":{"scope":"orbit"}},
              {"description":"lifetime is not an object","lifetime":42},
              {"description":"empty lifetime","lifetime":{}}
            ]""",
        )

        assertEquals(List(5) { null }, parsed.map { it.scope })
        // The sentence itself still renders — only the duration is withheld.
        assertEquals(5, parsed.size)
    }

    @Test
    fun `drops changes without a description but keeps the rest`() {
        val parsed = changes(
            """[
              {"lifetime":{"scope":"session"}},
              {"description":"   ","lifetime":{"scope":"session"}},
              {"description":"kept","lifetime":{"scope":"session"}}
            ]""",
        )

        assertEquals(listOf(PermissionOptionChange("kept", PermissionChangeScope.SESSION)), parsed)
    }

    @Test
    fun `caps the list and the per-description length`() {
        val long = (0..8).joinToString(",") {
            """{"description":"${"x".repeat(250)}$it","lifetime":{"scope":"session"}}"""
        }
        val parsed = changes("[$long]")

        assertEquals(6, parsed.size)
        assertEquals(200, parsed[0].description.length)
    }

    @Test
    fun `stops scanning long after it stops keeping`() {
        // Dropped entries do not end the loop, and an entry that arrives as a
        // JSON-encoded string is fully re-parsed to be examined — the outer
        // frame parse only read it as an opaque string token. Unbounded, that
        // is a second parse per entry on the composition thread.
        val junk = List(200) { """"{\"lifetime\":{\"scope\":\"session\"}}"""" }
        val parsed = changes("""[${junk.joinToString(",")},{"description":"past the cap"}]""")

        assertEquals(emptyList<PermissionOptionChange>(), parsed)

        // Slack enough that malformed entries ahead of a good one do not hide
        // it in any realistic payload.
        val nearMiss = List(31) { """{"lifetime":{"scope":"session"}}""" }
        assertEquals(
            listOf(PermissionOptionChange("found", null)),
            changes("""[${nearMiss.joinToString(",")},{"description":"found"}]"""),
        )
    }

    @Test
    fun `returns nothing for metadata it does not understand`() {
        // A future revision may reshape `changes`; showing it half-understood is
        // worse than showing nothing.
        fun parse(json: String) = parsePermissionOptionChanges(Json.parseToJsonElement(json))

        assertEquals(
            emptyList<PermissionOptionChange>(),
            parse("""{"permission":{"version":2,"changes":[{"description":"nope"}]}}"""),
        )
        assertEquals(
            emptyList<PermissionOptionChange>(),
            parse("""{"permission":{"version":1,"changes":{}}}"""),
        )
        // Version must be the number 1, not a string that merely parses as one:
        // the field is specified as a number, so a stringified one means some
        // layer reshaped the payload.
        assertEquals(
            emptyList<PermissionOptionChange>(),
            parse("""{"permission":{"version":"1","changes":[{"description":"nope"}]}}"""),
        )
        // No version at all: pre-spec metadata, same treatment.
        assertEquals(
            emptyList<PermissionOptionChange>(),
            parse("""{"permission":{"changes":[{"description":"nope"}]}}"""),
        )
        assertEquals(emptyList<PermissionOptionChange>(), parse("""{"somethingElse":true}"""))
        assertEquals(emptyList<PermissionOptionChange>(), parsePermissionOptionChanges(null))
    }

    @Test
    fun `a numeric version is compared as a number`() {
        // JSON has one number type, so `1.0` and `1e0` ARE 1 — the web
        // reference's `!== 1` accepts both. Rejecting them here would blank the
        // grants for a payload that is entirely valid.
        for (version in listOf("1", "1.0", "1e0")) {
            assertEquals(
                version,
                listOf(PermissionOptionChange("ok", null)),
                parsePermissionOptionChanges(
                    Json.parseToJsonElement(
                        """{"permission":{"version":$version,"changes":[{"description":"ok"}]}}""",
                    ),
                ),
            )
        }
        // ...but a different number is a different revision.
        assertEquals(
            emptyList<PermissionOptionChange>(),
            parsePermissionOptionChanges(
                Json.parseToJsonElement("""{"permission":{"version":1.5,"changes":[{"description":"no"}]}}"""),
            ),
        )
    }

    @Test
    fun `a meta that is not an object yields nothing rather than throwing`() {
        // `meta` is an untyped serde_json::Value upstream — any shape can arrive.
        for (raw in listOf("42", "\"text\"", "[]", "null", "true")) {
            assertEquals(
                raw,
                emptyList<PermissionOptionChange>(),
                parsePermissionOptionChanges(Json.parseToJsonElement(raw)),
            )
        }
    }

    @Test
    fun `any grant bounds the card body`() {
        // The card floats above the composer with nothing capping its height, so
        // an unbounded body pushes the option buttons off screen and the user
        // cannot answer at all. One grant is enough to need the bound: a single
        // description may be 200 characters.
        val bare = ParsedPermission.parse(Json.parseToJsonElement("""{"kind":"execute"}"""))
        assertFalse(permissionBodyScrolls(bare, hasGrants = false))
        assertTrue(permissionBodyScrolls(bare, hasGrants = true))
    }
}
