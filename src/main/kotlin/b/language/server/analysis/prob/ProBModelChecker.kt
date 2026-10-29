package b.language.server.analysis.prob

import b.language.server.analysis.ModelCheckReport
import b.language.server.analysis.ModelChecker
import b.language.server.analysis.Problem
import b.language.server.analysis.Severity
import b.language.server.config.Settings
import b.language.server.project.Location
import b.language.server.project.Project
import de.prob.exception.ProBError

/** Model checks with ProB; implementations load their kind of model and locate what is found. */
abstract class ProBModelChecker<in P : Project, M : LoadableModel>(
    protected val session: ProBSession,
) : ModelChecker<P> {
    final override fun modelCheck(
        project: P,
        settings: Settings,
    ): ModelCheckReport {
        session.unloadCurrent()
        val model = prepare(project) ?: return ModelCheckReport(emptyList(), "Model check skipped: the model has errors")
        val stateSpace = session.createStateSpace(ProBPreferences.checking(settings))
        return try {
            model.loadInto(stateSpace)
            when (val outcome = ModelCheckOutcome.of(stateSpace, settings.modelCheckStates)) {
                is ModelCheckOutcome.NoError -> ModelCheckReport(emptyList(), "Model check: ${outcome.message}")
                is ModelCheckOutcome.ErrorFound -> ModelCheckReport(locate(project, model, outcome), "Model check: ${outcome.message}")
            }
        } catch (e: ProBError) {
            ModelCheckReport(e.errors.toProblems(project.mainFile), "Model check failed")
        } finally {
            stateSpace.kill()
        }
    }

    /** The model of [project], or null if it has errors that prevent model checking. */
    protected abstract fun prepare(project: P): M?

    /** Where the error that model checking found shows in the sources. */
    protected abstract fun locate(
        project: P,
        model: M,
        error: ModelCheckOutcome.ErrorFound,
    ): List<Problem>

    /** The error, shown at the start of the main file for lack of a better place. */
    protected fun atStart(
        project: P,
        error: ModelCheckOutcome.ErrorFound,
    ): Problem {
        val message =
            when (error.kind) {
                ModelCheckOutcome.ErrorFound.Kind.INVARIANT_VIOLATION -> "Invariant violated${error.after}"
                ModelCheckOutcome.ErrorFound.Kind.DEADLOCK -> "Deadlock${error.after}"
                ModelCheckOutcome.ErrorFound.Kind.OTHER -> "${error.message}${error.after}"
            }
        return Problem(Location.ofToken(project.mainFile, 1, 1, 1), message, Severity.ERROR)
    }
}
