package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who owns a selector's remembered value, and whether what's on screen for it was ever
 * observed.
 *
 * Both questions exist because applies are not serialized — a context switch releases the row
 * lock while one is still in flight — and because the answer decides whether a failed apply
 * rewrites a preference the next connect will send.
 */
class ApplyRegistryTest {

    private val cline = AgentType.CLINE
    private val codex = AgentType.CODEX

    private fun key(agent: AgentType, selector: SelectorKey) = ApplyRegistry.Key(agent, selector)

    private val autoApprove = SelectorKey.Config("auto_approve")

    /** Start an apply and let its preference write succeed. */
    private fun ApplyRegistry.applied(key: ApplyRegistry.Key): Long =
        begin(key).also { claimWrite(key, it) }

    @Test
    fun `the apply that wrote the value owns it`() {
        val registry = ApplyRegistry()
        val token = registry.applied(key(cline, autoApprove))

        assertTrue(registry.owns(key(cline, autoApprove), token))
    }

    @Test
    fun `starting an apply does not by itself take ownership`() {
        // The save can fail. An apply that never wrote must not take responsibility away from an
        // earlier one that did and still owes a rollback.
        val registry = ApplyRegistry()
        val wrote = registry.applied(key(cline, autoApprove))
        registry.begin(key(cline, autoApprove))

        assertTrue(registry.owns(key(cline, autoApprove), wrote))
    }

    @Test
    fun `a later apply takes ownership away`() {
        // The ABA case: #1 turns it off, #2 on, #3 off again. #1 then fails. The store now holds
        // "false" — exactly what #1 wrote — so a value comparison would happily undo #3's pick.
        val registry = ApplyRegistry()
        val first = registry.applied(key(cline, autoApprove))
        registry.applied(key(cline, autoApprove))
        val third = registry.applied(key(cline, autoApprove))

        assertFalse(registry.owns(key(cline, autoApprove), first))
        assertTrue(registry.owns(key(cline, autoApprove), third))
    }

    @Test
    fun `another agent claiming the same selector does not take ownership`() {
        // Preferences are stored per agent, so agent B's apply cannot have overwritten agent A's
        // entry. Treating B's claim as superseding would make A skip a rollback it still owes.
        val registry = ApplyRegistry()
        val clineToken = registry.applied(key(cline, SelectorKey.Mode))
        registry.applied(key(codex, SelectorKey.Mode))

        assertTrue(registry.owns(key(cline, SelectorKey.Mode), clineToken))
    }

    @Test
    fun `a different selector on the same agent is independent`() {
        val registry = ApplyRegistry()
        val modeToken = registry.applied(key(cline, SelectorKey.Mode))
        registry.applied(key(cline, autoApprove))

        assertTrue(registry.owns(key(cline, SelectorKey.Mode), modeToken))
    }

    @Test
    fun `a selector nobody wrote is owned by nobody`() {
        assertFalse(ApplyRegistry().owns(key(cline, autoApprove), token = 1L))
    }

    @Test
    fun `only the newest apply for a selector counts as latest`() {
        // Decides which completion may publish provenance: an older apply finishing later
        // describes a value that has already been replaced on screen.
        val registry = ApplyRegistry()
        val older = registry.begin(key(cline, autoApprove))
        val newer = registry.begin(key(cline, autoApprove))

        assertFalse(registry.isLatest(key(cline, autoApprove), older))
        assertTrue(registry.isLatest(key(cline, autoApprove), newer))
        // Independent per agent and per selector, like ownership.
        assertFalse(registry.isLatest(key(codex, autoApprove), newer))
        assertFalse(registry.isLatest(key(cline, SelectorKey.Mode), newer))
    }

    @Test
    fun `values start unobserved`() {
        assertFalse(ApplyRegistry().isObserved(key(cline, autoApprove)))
    }

    @Test
    fun `a live snapshot marks every selector it carried as observed`() {
        val registry = ApplyRegistry()
        registry.setObserved(cline, listOf(SelectorKey.Mode, autoApprove), observedValue = true)

        assertTrue(registry.isObserved(key(cline, SelectorKey.Mode)))
        assertTrue(registry.isObserved(key(cline, autoApprove)))
        // Per agent: the same selector on another agent was not part of that load.
        assertFalse(registry.isObserved(key(codex, autoApprove)))
    }

    @Test
    fun `an unconfirmed apply leaves the value unobserved for the next one`() {
        // The reconcile budget elapsed, so what's on screen is the optimistic pick. Carrying it
        // forward as a baseline would let the agent's untouched default look like a
        // normalization on the following apply.
        val registry = ApplyRegistry()
        registry.setObserved(cline, listOf(autoApprove), observedValue = true)
        registry.setObserved(key(cline, autoApprove), observedValue = false)

        assertFalse(registry.isObserved(key(cline, autoApprove)))
    }

    @Test
    fun `a catalog probe clears observation left by an earlier live load`() {
        val registry = ApplyRegistry()
        registry.setObserved(cline, listOf(SelectorKey.Mode, autoApprove), observedValue = true)
        registry.setObserved(cline, listOf(SelectorKey.Mode, autoApprove), observedValue = false)

        assertFalse(registry.isObserved(key(cline, SelectorKey.Mode)))
        assertFalse(registry.isObserved(key(cline, autoApprove)))
    }

    @Test
    fun `generation counts both ends of an apply`() {
        // `refreshLiveSelectors` captures this before a read that takes a while, to tell whether
        // the values it is about to publish were superseded meanwhile. A COMPLETION moves the
        // value exactly as much as a start, so counting only starts would let a read that
        // straddled the end of an apply overwrite the pick that apply just landed.
        val registry = ApplyRegistry()
        val start = registry.generation

        registry.begin(key(cline, autoApprove))
        val begun = registry.generation
        assertTrue(begun > start)

        registry.settle()
        assertTrue(registry.generation > begun)
    }

    @Test
    fun `generation is stable while nothing is applying`() {
        // Otherwise every refresh would abandon itself.
        val registry = ApplyRegistry()
        registry.setObserved(cline, listOf(autoApprove), observedValue = true)
        val quiet = registry.generation

        registry.isObserved(key(cline, autoApprove))
        registry.setObserved(key(cline, autoApprove), observedValue = false)

        assertEquals(quiet, registry.generation)
    }
}
