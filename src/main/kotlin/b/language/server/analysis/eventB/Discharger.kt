package b.language.server.analysis.eventB

import arrow.core.Either
import arrow.core.getOrElse
import b.language.server.analysis.ProofObligation
import b.language.server.analysis.Verdict
import b.language.server.analysis.eventB.ObligationGoal.Companion.PRIME_SUFFIX
import b.language.server.analysis.eventB.ProBTranslation.Companion.NO_EXTENSIONS
import b.language.server.analysis.prob.ProBFailure
import b.language.server.analysis.prob.ProBPreferences
import b.language.server.analysis.prob.ProBSession
import de.prob.animator.command.CbcSolveCommand
import de.prob.animator.domainobjects.ComputationNotCompletedResult
import de.prob.animator.domainobjects.EvalResult
import de.prob.animator.domainobjects.EventB
import de.prob.animator.domainobjects.FormulaExpand
import de.prob.model.eventb.Context
import de.prob.model.eventb.EventBModel
import de.prob.model.representation.ModelElementList
import de.prob.statespace.StateSpace
import java.util.logging.Logger
import de.prob.model.representation.Set as ProBSet

/**
 * Decides proof obligations with ProB's constraint solvers: an obligation is discharged if its
 * negation has no solution, and disproved by a solution, which is a counterexample.
 */
class Discharger(
    private val session: ProBSession,
) {
    private val solvers = listOf(CbcSolveCommand.Solvers.PROB, CbcSolveCommand.Solvers.Z3AXM)

    fun discharge(
        main: CheckedComponent,
        goals: List<ObligationGoal>,
        timeoutMillis: Int,
    ): List<ProofObligation> {
        if (goals.isEmpty()) return emptyList()
        // The obligations mention constants and variables freely; only the carrier sets must be known.
        val sets =
            Context("proof_obligations").withSets(
                ModelElementList(
                    main.contexts
                        .flatMap { it.sets }
                        .distinct()
                        .map { ProBSet(EventB(it, NO_EXTENSIONS)) },
                ),
            )
        val verdicts =
            session.withStateSpace(ProBPreferences(timeoutMillis = timeoutMillis)) { stateSpace ->
                ProBFailure
                    .catching { session.instance(EventBModel::class.java).addContext(sets).loadIntoStateSpace(stateSpace, sets) }
                    .map { goals.map { solve(stateSpace, it) } }
                    .getOrElse { failure ->
                        log.info("could not discharge proof obligations: ${failure.description}")
                        goals.map { Verdict.Undischarged("") }
                    }
            }
        return goals.zip(verdicts) { goal, verdict -> ProofObligation(goal.name, goal.location, verdict) }
    }

    /** Asks each solver in turn; the reason of the last one that could not decide is kept. */
    private fun solve(
        stateSpace: StateSpace,
        goal: ObligationGoal,
    ): Verdict {
        var reason = ""
        for (solver in solvers) {
            val attempt =
                ProBFailure.catching {
                    CbcSolveCommand(
                        EventB(goal.negation, NO_EXTENSIONS, FormulaExpand.EXPAND),
                        solver,
                    ).also { stateSpace.execute(it) }.value
                }
            when (attempt) {
                is Either.Left -> {
                    val failure = attempt.value
                    reason = failure.errors.firstOrNull()?.message ?: failure.description
                        ?.lineSequence()
                        ?.firstOrNull()
                        .orEmpty()
                }

                is Either.Right -> {
                    val result = attempt.value
                    if (result is EvalResult && result.value == "FALSE") return Verdict.Discharged
                    if (result is EvalResult && result.value == "TRUE") {
                        return Verdict.Disproved(
                            result.solutions.entries.joinToString(", ") { (name, value) ->
                                "${name.replace(Regex("$PRIME_SUFFIX$"), "'")} = $value"
                            },
                        )
                    }
                    reason = (result as? ComputationNotCompletedResult)?.reason ?: result.toString()
                }
            }
        }
        return Verdict.Undischarged(reason)
    }

    private companion object {
        val log: Logger = Logger.getLogger(Discharger::class.java.name)
    }
}
