package b.language.server.analysis.eventB

import b.language.server.Fixtures
import b.language.server.analysis.LastCheck
import b.language.server.analysis.ProofObligation
import b.language.server.project.SourcePosition
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventBHoverTest {
    private val hover = EventBHover(LastCheck<List<ProofObligation>>(emptyList()))
    private val counter = Fixtures.eventB("Counter")

    /** The hover over the first occurrence of [text] in [line]. */
    private fun at(
        line: Int,
        text: String,
    ): String {
        val column = counter.text.lines()[line].indexOf(text)
        return assertNotNull(hover.hover(counter, SourcePosition(line, column)), "no hover at $text").text
    }

    @Test
    fun `keywords are documented`() {
        assertTrue(at(0, "machine").startsWith("```eventb\nmachine name refines abstract sees context"))
    }

    @Test
    fun `variables show their type and the invariants about them`() {
        val text = at(1, "n")
        assertTrue("(variable) n ∈ ℤ" in text, text)
        assertTrue("@inv1 n ∈ 0 ‥ limit" in text, text)
    }

    @Test
    fun `labels tell what they label`() {
        assertTrue("`@inv1` is an invariant of `Counter`." in at(3, "inv1"))
    }

    @Test
    fun `bound variables show their type`() {
        assertTrue("(bound variable) x ∈ ℤ" in at(22, "x·"))
    }

    @Test
    fun `operators are documented from the Rodin Handbook`() {
        assertTrue("Rodin Handbook" in at(3, "∈"))
    }
}
