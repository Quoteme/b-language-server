package b.language.server.analysis.eventB

import b.language.server.Fixtures
import b.language.server.analysis.Severity
import b.language.server.project.SourcePosition
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StaticCheckerTest {
    private fun check(name: String) = StaticChecker(Fixtures.eventB(name)).check()

    @Test
    fun `a correct machine is checked with the contexts it sees`() {
        val result = check("Bank")
        assertFalse(result.hasErrors)
        val bank = assertIs<CheckedMachine>(result.main)
        assertEquals(listOf("clients", "account_access"), bank.variables)
        assertEquals(listOf("INITIALISATION", "add_client", "connect_account"), bank.events.keys.toList())
        assertEquals(listOf("Bank_ctx"), bank.contexts.map { it.name })
    }

    @Test
    fun `a context keeps its constants and theorems`() {
        val context = assertIs<CheckedContext>(check("Counter_ctx").main)
        assertEquals(listOf("limit"), context.constants)
        assertEquals(listOf("axm1" to false, "thm1" to true), context.axioms.map { it.label to it.theorem })
    }

    @Test
    fun `type errors and undeclared identifiers are reported`() {
        val problems = check("TypeErrors").problems
        assertEquals(
            listOf("Type: ℤ does not match type: PERSONS", "Identifier 'cuont' has not been declared"),
            problems.map { it.message },
        )
        assertTrue(problems.all { it.severity == Severity.ERROR })
    }

    @Test
    fun `refinement rules of Rodin are checked`() {
        val problems = check("Counter_r1").problems.map { it.severity to it.message }
        assertEquals(
            listOf(
                Severity.ERROR to "Convergent event 'inc' requires a variant",
                Severity.WARNING to "Abstract event 'check' is not refined",
            ),
            problems,
        )
    }

    @Test
    fun `syntax errors in formulas are located`() {
        val problem = check("SyntaxError").problems.single()
        assertEquals("Premature End Of Formula", problem.message)
        assertEquals(SourcePosition(5, 14), problem.location.start)
    }
}
