package b.language.server.analysis.eventB

import arrow.core.Either
import arrow.core.raise.either
import b.language.server.analysis.AnalysisFailure
import b.language.server.analysis.Checker
import b.language.server.analysis.LastCheck
import b.language.server.analysis.Problem
import b.language.server.analysis.ProofObligation
import b.language.server.analysis.Severity
import b.language.server.analysis.Verdict
import b.language.server.analysis.prob.ProBHome
import b.language.server.analysis.prob.ProBSession
import b.language.server.config.Settings
import b.language.server.project.eventB.EventBProject

/**
 * Checks an Event-B component like Rodin does: static checks, then proof obligations, which ProB
 * tries to discharge or disprove.
 */
class EventBChecker(
    private val session: ProBSession,
    private val obligations: LastCheck<List<ProofObligation>>,
) : Checker<EventBProject> {
    private val discharger = Discharger(session)

    override fun check(
        project: EventBProject,
        settings: Settings,
    ): Either<AnalysisFailure, List<Problem>> =
        either {
            session.use(ProBHome.of(settings.probHome)).bind()
            session.unloadCurrent()
            val result = StaticChecker(project).check()
            obligations.forget(project.mainFile)
            val main = result.main
            if (result.hasErrors || main == null) return@either result.problems

            val goals = ProofObligationGenerator().generate(main)
            val decided = discharger.discharge(main, goals, settings.poTimeout)
            obligations[project.mainFile] = decided
            result.problems + decided.mapNotNull { it.toProblem() }
        }

    private fun ProofObligation.toProblem(): Problem? =
        when (verdict) {
            Verdict.Discharged -> {
                null
            }

            is Verdict.Disproved -> {
                val example = if (verdict.counterexample.isEmpty()) "" else ", e.g. for ${verdict.counterexample}"
                Problem(location, "$name does not hold$example", Severity.WARNING)
            }

            is Verdict.Undischarged -> {
                Problem(location, "$name is not discharged by ProB: ${verdict.reason}", Severity.HINT)
            }
        }
}
