package b.language.server.analysis.eventB

import arrow.core.Either
import b.language.server.analysis.Problem
import b.language.server.analysis.Severity
import b.language.server.analysis.eventB.ProBTranslation.Companion.NO_EXTENSIONS
import b.language.server.analysis.prob.LoadFailure
import b.language.server.analysis.prob.LoadableModel
import b.language.server.analysis.prob.ModelCheckOutcome
import b.language.server.analysis.prob.ModelCheckOutcome.ErrorFound.Kind
import b.language.server.analysis.prob.ProBModelChecker
import b.language.server.analysis.prob.ProBSession
import b.language.server.project.Location
import b.language.server.project.eventB.EventBProject
import de.prob.animator.domainobjects.EvalResult
import de.prob.animator.domainobjects.EventB
import de.prob.model.eventb.EventBModel
import de.prob.statespace.StateSpace

/** Model checks an Event-B machine and shows violations at the invariants they break. */
class EventBModelChecker(
    session: ProBSession,
) : ProBModelChecker<EventBProject, EventBModelChecker.Model>(session) {
    /** The checked model and its translation for ProB. */
    class Model(
        val main: CheckedComponent,
        private val translation: ProBTranslation,
    ) : LoadableModel {
        override fun loadInto(stateSpace: StateSpace): Either<LoadFailure, Unit> = translation.loadInto(stateSpace)
    }

    override fun prepare(project: EventBProject): Model? {
        val result = StaticChecker(project).check()
        val main = result.main
        if (result.hasErrors || main == null) return null
        return Model(main, ProBTranslation.of(main, session.instance(EventBModel::class.java)))
    }

    override fun locate(
        project: EventBProject,
        model: Model,
        error: ModelCheckOutcome.ErrorFound,
    ): List<Problem> {
        val machine = model.main as? CheckedMachine ?: return listOf(atStart(project, error))
        if (error.kind == Kind.INVARIANT_VIOLATION) {
            val state = error.trace.currentState
            val violated =
                machine.invariants.filter { invariant ->
                    (state.eval(EventB(invariant.formula.toString(), NO_EXTENSIONS)) as? EvalResult)?.value == "FALSE"
                }
            if (violated.isEmpty()) return listOf(atStart(project, error))
            return violated.map { invariant ->
                val token = invariant.token
                Problem(
                    Location.ofToken(machine.file, token.line, token.pos, token.text.length),
                    "Invariant '${invariant.label}' violated${error.after}",
                    Severity.ERROR,
                )
            }
        }
        val name = machine.component.name
        val message = if (error.kind == Kind.DEADLOCK) "Deadlock${error.after}" else "${error.message}${error.after}"
        return listOf(Problem(Location.ofToken(machine.file, name.line, name.pos, name.text.length), message, Severity.ERROR))
    }
}
