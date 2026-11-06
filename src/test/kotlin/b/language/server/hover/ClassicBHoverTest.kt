package b.language.server.hover

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ClassicBHoverTest {
    private fun hover(text: String, column: Int) = ClassicBHover.hover(text, 0, column)

    @Test
    fun operators_showTheirDocumentationAndSource() {
        val shown = assertNotNull(hover("""S \/ T""", 2))

        assertContains(shown, "```b\nS\\/T\n```")
        assertContains(shown, "Set union")
        assertContains(shown, "https://prob.hhu.de/w/index.php?title=Summary_of_B_Syntax#Sets")
    }

    @Test
    fun examples_followTheDescription() {
        assertContains(assertNotNull(hover("""S \/ T""", 2)), "Set union\n\n**Example**\n```b\n{1, 2} \\/ {2, 3} = {1, 2, 3}\n```")
    }

    @Test
    fun theLongestOperator_wins() {
        assertContains(assertNotNull(hover("a<=>b", 2)), "P <=> Q")
    }

    @Test
    fun unicodeSymbols_readLikeTheirAscii() {
        assertContains(assertNotNull(hover("S ∪ T", 2)), "Set union")
    }

    @Test
    fun keywords_areDocumented() {
        assertContains(assertNotNull(hover("  card(S)", 3)), "card(S)")
    }

    @Test
    fun keywordsOnlyMentionedInProse_haveHandWrittenDocumentation() {
        val shown = assertNotNull(hover("IMPORTS Storage", 2))

        assertContains(shown, "Machines imported by an implementation.")
        assertContains(shown, "Summary_of_B_Syntax#Machine_inclusion")
    }

    @Test
    fun partsOfAConstruct_showTheConstruct() {
        assertContains(assertNotNull(hover("  THEN x := 1", 3)), "IF")
    }

    @Test
    fun undocumentedText_showsNothing() {
        assertEquals(null, hover("order", 2))
        assertEquals(null, hover("   ", 1))
        assertEquals(null, hover("x", 40))
        assertEquals(null, ClassicBHover.hover("x", 9, 0))
    }
}
