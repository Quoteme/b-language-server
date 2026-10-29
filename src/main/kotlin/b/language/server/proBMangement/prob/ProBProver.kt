package b.language.server.proBMangement.prob

import com.google.inject.Injector
import de.prob.animator.ReusableAnimator
import de.prob.animator.command.CbcSolveCommand
import de.prob.animator.domainobjects.ComputationNotCompletedResult
import de.prob.animator.domainobjects.EvalResult
import de.prob.animator.domainobjects.EventB
import de.prob.animator.domainobjects.FormulaExpand
import de.prob.exception.ProBError
import de.prob.model.eventb.Context
import de.prob.model.eventb.EventBModel
import de.prob.model.representation.ModelElementList
import de.prob.model.representation.Set as ProBSet

/**
 * ProB's constraint solver as a prover: a sequent holds if the conjunction of its hypotheses and
 * its negated goal has no solution; a solution is a counterexample.
 *
 * The predicates mention constants and variables freely, so only the carrier sets of [sets] are
 * loaded, as a context of their own.
 */
class ProBProver(injector: Injector, animator: ReusableAnimator, sets: Collection<String>, timeout: Int) : AutoCloseable {

    sealed class Result {
        object Proved : Result()
        class Counterexample(val detail: String) : Result()
        class Unknown(val reason: String) : Result()
    }

    private val noExtensions = emptySet<org.eventb.core.ast.extension.IFormulaExtension>()
    private val stateSpace = animator.createStateSpace()

    init {
        try {
            val context = Context("proof_obligations").withSets(ModelElementList(sets.distinct().map { ProBSet(EventB(it, noExtensions)) }))
            stateSpace.changePreferences(mapOf("TIME_OUT" to timeout.toString()))
            injector.getInstance(EventBModel::class.java).addContext(context).loadIntoStateSpace(stateSpace, context)
        } catch (e: RuntimeException) {
            stateSpace.kill()
            throw e
        }
    }

    /** Solves [negation] with ProB's solver, then with Z3 if ProB cannot decide. */
    fun solve(negation: String): Result {
        var reason = ""
        for (solver in listOf(CbcSolveCommand.Solvers.PROB, CbcSolveCommand.Solvers.Z3AXM)) {
            val result = try {
                val command = CbcSolveCommand(EventB(negation, noExtensions, FormulaExpand.EXPAND), solver)
                stateSpace.execute(command)
                command.value
            } catch (e: ProBError) {
                reason = e.errors.firstOrNull()?.message ?: e.message?.lineSequence()?.firstOrNull().orEmpty()
                continue
            }
            if (result is EvalResult && result.value == "FALSE") return Result.Proved
            if (result is EvalResult && result.value == "TRUE") {
                return Result.Counterexample(result.solutions.entries.joinToString(", ") { (name, value) ->
                    "${name.replace(Regex("$PRIME_SUFFIX$"), "'")} = $value"
                })
            }
            reason = (result as? ComputationNotCompletedResult)?.reason ?: result.toString()
        }
        return Result.Unknown(reason)
    }

    override fun close() = stateSpace.kill()

    companion object {
        /** The carrier sets the obligations of [project] may mention. */
        fun carrierSets(project: CamilleProject): List<String> = when (val info = project.mainInfo) {
            is MachineInfo -> info.contexts
            is ContextInfo -> info.closure().toList()
            else -> emptyList()
        }.flatMap { it.sets }.distinct()
    }
}
