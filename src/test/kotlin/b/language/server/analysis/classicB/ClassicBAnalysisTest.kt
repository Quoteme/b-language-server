package b.language.server.analysis.classicB

import arrow.core.getOrElse
import b.language.server.Fixtures
import b.language.server.analysis.CandidateKind
import b.language.server.analysis.prob.startProB
import b.language.server.config.Settings
import b.language.server.project.SourcePosition
import b.language.server.project.classicB.ClassicBProject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Checks classical B with ProB, and the hovers and completions that the check makes possible. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClassicBAnalysisTest {
    private val session = startProB()
    private val analysis = ClassicBAnalysis(session)

    @AfterAll
    fun close() = session.close()

    private fun messages(
        path: String,
        settings: Settings = Settings(),
    ) = analysis.check(Fixtures.classicB(path), settings).getOrElse { fail(it.message) }.map { it.message }

    @Test
    fun `a correct machine has no problems`() {
        assertEquals(emptyList(), messages("classicB/Doors.mch"))
    }

    @Test
    fun `well-definedness and extended static checks follow the settings`() {
        val wd = "WD-PO [AXM] not discharged: x /= 0"
        val unused = "Variable is written by no operation (consider defining it as a constant): v"
        assertEquals(setOf(wd, unused, "This constant is not used outside of the PROPERTIES/axioms: x"), messages("WD_M1.mch").toSet())
        assertTrue(wd !in messages("WD_M1.mch", Settings(wdChecks = false)))
        assertEquals(listOf(wd), messages("WD_M1.mch", Settings(strictChecks = false)))
    }

    @Test
    fun `problems in included machines are reported in their own file`() {
        val problems = analysis.check(Fixtures.classicB("WD_M2.mch"), Settings()).getOrElse { fail(it.message) }
        assertEquals(setOf("WD_M1.mch", "WD_M2.mch"), problems.map { it.location.file.name }.toSet())
    }

    @Test
    fun `hovers show the identifiers as ProB typed them in the last check`() {
        val doors = Fixtures.classicB("classicB/Doors.mch")
        analysis.check(doors, Settings())
        val hover = assertNotNull(analysis.hover(doors, SourcePosition(5, 2))).text
        assertTrue(hover.startsWith("```b\n(constant) capacity : INTEGER"), hover)
        assertTrue("(enumerated set) DOOR = {north, south}" in assertNotNull(analysis.hover(doors, SourcePosition(2, 2))).text)
    }

    @Test
    fun `completions offer identifiers with their kind, and keywords`() {
        val doors = Fixtures.classicB("classicB/Doors.mch")
        analysis.check(doors, Settings())

        fun complete(typed: String) = analysis.complete(ClassicBProject(doors.mainFile, typed), SourcePosition(0, typed.length))
        assertEquals(CandidateKind.CONSTANT, complete("cap").single { it.word == "capacity" }.kind)
        assertEquals(CandidateKind.FUNCTION, complete("cou").single { it.word == "count" }.kind)
        assertEquals(CandidateKind.ENUM_MEMBER, complete("nor").single { it.word == "north" }.kind)
        assertEquals(CandidateKind.KEYWORD, complete("INVA").single { it.word == "INVARIANT" }.kind)
    }

    @Test
    fun `model checking finds a deadlock-free machine`() {
        val report = analysis.modelCheck(Fixtures.classicB("classicB/Doors.mch"), Settings())
        assertEquals(emptyList(), report.problems)
        assertTrue(report.summary.startsWith("Model check: "), report.summary)
    }
}
