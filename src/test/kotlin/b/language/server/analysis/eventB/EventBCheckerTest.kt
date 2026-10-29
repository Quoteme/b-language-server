package b.language.server.analysis.eventB

import arrow.core.getOrElse
import b.language.server.Fixtures
import b.language.server.analysis.Severity
import b.language.server.analysis.Verdict
import b.language.server.analysis.prob.ProBUnavailable
import b.language.server.analysis.prob.startProB
import b.language.server.config.Settings
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Checks with ProB, which tries to discharge or disprove the proof obligations. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventBCheckerTest {
    private val session = startProB()
    private val analysis = EventBAnalysis(session)

    @AfterAll
    fun close() = session.close()

    @Test
    fun `disproved obligations are warnings with a counterexample`() {
        val counter = Fixtures.eventB("Counter")
        val problems = analysis.check(counter, Settings()).getOrElse { fail(it.message) }
        val disproved = problems.filter { it.severity == Severity.WARNING }.map { it.message.substringBefore(" does not hold") }
        assertEquals(listOf("inc/inv2/INV", "add/inv2/INV", "check/inv2/INV"), disproved)

        val obligations = analysis.proofObligations(counter).associate { it.name to it.verdict }
        assertEquals(Verdict.Discharged, obligations["INITIALISATION/inv1/INV"])
        assertIs<Verdict.Disproved>(obligations["inc/inv2/INV"])
    }

    @Test
    fun `models with errors get no proof obligations`() {
        val typeErrors = Fixtures.eventB("TypeErrors")
        val problems = analysis.check(typeErrors, Settings()).getOrElse { fail(it.message) }
        assertTrue(problems.all { it.severity == Severity.ERROR })
        assertEquals(emptyList(), analysis.proofObligations(typeErrors))
    }

    @Test
    fun `a ProB home that does not exist is a failure, not a crash`() {
        val failure = analysis.check(Fixtures.eventB("Counter"), Settings(probHome = "/does/not/exist")).leftOrNull()
        assertIs<ProBUnavailable.HomeNotFound>(failure)
    }
}
