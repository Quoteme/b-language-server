package b.language.server.analysis.eventB

import b.language.server.Fixtures
import b.language.server.analysis.CandidateKind
import b.language.server.analysis.LastCheck
import b.language.server.analysis.ProofObligation
import b.language.server.project.SourcePosition
import b.language.server.project.eventB.EventBProject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventBCompletionTest {
    private val completion = EventBCompletion(LastCheck<List<ProofObligation>>(emptyList()))

    /** Completes [typed] as if it were written in Counter.mch, whose checked model provides the identifiers. */
    private fun complete(typed: String) =
        completion.complete(EventBProject(Fixtures.file("eventb/Counter.mch"), typed), SourcePosition(0, typed.length))

    @Test
    fun `identifiers of the model are completed with their kind`() {
        val limit = complete("li").single { it.word == "limit" }
        assertEquals(CandidateKind.CONSTANT, limit.kind)
        assertTrue(
            limit.documentation
                ?.text
                .orEmpty()
                .contains("(constant) limit ∈ ℤ"),
        )
    }

    @Test
    fun `keywords are completed`() {
        assertEquals(CandidateKind.KEYWORD, complete("conv").single { it.word == "convergent" }.kind)
    }

    @Test
    fun `nothing is offered without a word to complete`() {
        assertEquals(emptyList(), complete(""))
    }
}
