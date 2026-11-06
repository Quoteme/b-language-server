package b.language.server.generator

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenerateDocumentationTest {
    private val page =
        """
        === Sets ===
        <pre>
         S\/T         set union
         card(S)      cardinality
        </pre>
        Prose outside a table is ignored.
        === Machine sections ===
        <pre>
         INVARIANT    P     (predicate that must hold)
        </pre>
        === Identifiers ===
        <pre>
         ignored      a section of explanations
        </pre>
        === Statements (aka Substitutions) ===
        <pre>
         IF P THEN G END     conditional
        </pre>
        """.trimIndent()

    private val entries = parseSyntaxSummary(page)

    @Test
    fun entries_keepTheirTextAndLinkToTheirSection() {
        val union = entries.single { it.key == """\/""" }

        assertEquals("""S\/T""", union.title)
        assertEquals("set union", union.description)
        assertEquals("https://prob.hhu.de/w/index.php?title=Summary_of_B_Syntax#Sets", union.link)
        assertEquals("ProB, Summary of B Syntax: Sets", union.source)
    }

    @Test
    fun machineSections_haveAnArgumentColumn() {
        val invariant = entries.single { it.key == "INVARIANT" }

        assertEquals("INVARIANT P", invariant.title)
        assertEquals("predicate that must hold", invariant.description)
    }

    @Test
    fun onlyTheFirstKeyNamesAConstruct() {
        assertEquals(listOf("IF", "THEN", "END"), entries.filter { it.title.startsWith("IF") }.map { it.key })
        assertEquals(listOf("IF"), entries.filter { it.title.startsWith("IF") && it.primary }.map { it.key })
    }

    @Test
    fun explanationSections_areSkipped() {
        assertTrue(entries.none { it.title == "ignored" })
    }

    @Test
    fun keys_leaveOutPlaceholders() {
        assertEquals(listOf("card"), keysOf("card(S)"))
        assertEquals(listOf("or"), keysOf("P or Q"))
        assertEquals(listOf(".."), keysOf("n..m"))
    }

    @Test
    fun examples_joinTheEntryWithTheirTitle() {
        val joined = withExamples(entries, """{"card(S)": "card({1}) = 1"}""")

        assertEquals("card({1}) = 1", joined.single { it.key == "card" }.example)
        assertEquals(null, joined.single { it.key == "INVARIANT" }.example)
    }

    @Test
    fun everyExample_belongsToADocumentedTitle() {
        val examples = Gson().fromJson(javaClass.getResource("/examples.json")!!.readText(), JsonObject::class.java)
        val committed = javaClass.getResource("/documentation/classical.json")!!.readText()
        val titles = Gson().fromJson(committed, Array<Entry>::class.java).map { it.title }

        assertTrue(titles.containsAll(examples.keySet()))
    }

    @Test
    fun committedDocumentation_isWellFormed() {
        val text = javaClass.getResource("/documentation/classical.json")!!.readText()

        val committed = Gson().fromJson(text, Array<Entry>::class.java).map { it.key }

        assertTrue(committed.toSet().size > 200)
        assertContains(committed, "card")
        assertContains(committed, "IMPORTS")
    }
}
