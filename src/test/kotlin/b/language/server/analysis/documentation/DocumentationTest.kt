package b.language.server.analysis.documentation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocumentationTest {
    @Test
    fun `the tables are read from the resources`() {
        assertTrue(DocumentationTables.eventB.keys.size > 100)
        assertTrue(DocumentationTables.classicB.keys.size > 200)
        val union = assertNotNull(DocumentationTables.eventB["∪"]).first()
        assertEquals("∪", union.symbol)
        assertTrue(union.wd.isNotEmpty())
        assertEquals("⊤", DocumentationTables.extras(CodeLanguage.EVENT_B, "True")?.signature)
    }

    @Test
    fun `words, labels and operators are found under the cursor`() {
        val line = LineText("  @inv1 n' ∈ 0 ‥ limit")
        assertEquals("@inv1", line.wordAt(4))
        assertEquals("n'", line.wordAt(8))
        assertEquals(null, line.wordAt(11))
        assertEquals("∈", line.operatorAt(11, DocumentationTables.eventB)?.first()?.key)
        assertEquals("lim", line.prefixBefore(20))
    }

    @Test
    fun `cards show the signature, a rule, the text, examples and links`() {
        val card =
            DocumentationCard(CodeLanguage.CLASSIC_B, "x : NAT", listOf("A variable."), listOf("x = 1"), listOf(Link("https://a", "A")))
        assertEquals("```b\nx : NAT\n```\n\n---\n\nA variable.\n\n**Example**\n```b\nx = 1\n```\n\n[A](https://a)", card.render().text)
    }
}
