package b.language.server.analysis.eventB

import b.language.server.analysis.ProofObligation
import b.language.server.analysis.Verdict
import b.language.server.analysis.eventB.ObligationGoal.Companion.PRIME_SUFFIX
import b.language.server.analysis.eventB.ProBTranslation.Companion.NO_EXTENSIONS
import b.language.server.analysis.prob.ProBPreferences
import b.language.server.analysis.prob.ProBSession
import de.prob.animator.command.CbcSolveCommand
import de.prob.animator.domainobjects.ComputationNotCompletedResult
import de.prob.animator.domainobjects.EvalResult
import de.prob.animator.domainobjects.EventB
import de.prob.animator.domainobjects.FormulaExpand
import de.prob.exception.ProBError
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
                ModelElementList(main.contexts.flatMap { it.sets }.distinct().map { ProBSet(EventB(it, NO_EXTENSIONS)) }),
            )
        val verdicts = mutableListOf<Verdict>()
        val stateSpace = session.createStateSpace(ProBPreferences(timeoutMillis = timeoutMillis))
        try {
            session.instance(EventBModel::class.java).addContext(sets).loadIntoStateSpace(stateSpace, sets)
            goals.forEach { verdicts += solve(stateSpace, it) }
        } catch (e: ProBError) {
            log.info("could not discharge proof obligations: ${e.message}")
        } finally {
            stateSpace.kill()
        }
        return goals.mapIndexed { index, goal ->
            ProofObligation(goal.name, goal.location, verdicts.getOrElse(index) { Verdict.Undischarged("") })
        }
    }

    private fun solve(
        stateSpace: StateSpace,
        goal: ObligationGoal,
    ): Verdict {
        var reason = ""
        for (solver in solvers) {
            val result =
                try {
                    val command = CbcSolveCommand(EventB(goal.negation, NO_EXTENSIONS, FormulaExpand.EXPAND), solver)
                    stateSpace.execute(command)
                    command.value
                } catch (e: ProBError) {
                    reason = e.errors.firstOrNull()?.message ?: e.message
                        ?.lineSequence()
                        ?.firstOrNull()
                        .orEmpty()
                    continue
                }
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
        return Verdict.Undischarged(reason)
    }

    private companion object {
        val log: Logger = Logger.getLogger(Discharger::class.java.name)
    }
}
