package b.language.server.analysis.eventB

import b.language.server.Fixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ProofObligationGeneratorTest {
    private fun obligations(name: String): List<String> {
        val main = StaticChecker(Fixtures.eventB(name)).check().main ?: error("$name does not check")
        return ProofObligationGenerator().generate(main).map { it.name }
    }

    @Test
    fun `a theorem of a context must be proven`() {
        assertEquals(listOf("thm1/THM"), obligations("Counter_ctx"))
    }

    @Test
    fun `events must preserve the invariants and convergent events decrease the variant`() {
        assertEquals(
            listOf(
                "INITIALISATION/inv1/INV",
                "INITIALISATION/inv2/INV",
                "inc/inv1/INV",
                "inc/inv2/INV",
                "inc/NAT",
                "inc/VAR",
                "add/inv1/INV",
                "add/inv2/INV",
                "check/act1/FIS",
                "check/inv1/INV",
                "check/inv2/INV",
            ),
            obligations("Counter"),
        )
    }
}
