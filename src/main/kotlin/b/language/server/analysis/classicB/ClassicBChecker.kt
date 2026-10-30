package b.language.server.analysis.classicB

import arrow.core.Either
import arrow.core.raise.either
import b.language.server.analysis.AnalysisFailure
import b.language.server.analysis.Checker
import b.language.server.analysis.LastCheck
import b.language.server.analysis.Problem
import b.language.server.analysis.prob.LoadFailure
import b.language.server.analysis.prob.ProBFailure
import b.language.server.analysis.prob.ProBHome
import b.language.server.analysis.prob.ProBPreferences
import b.language.server.analysis.prob.ProBSession
import b.language.server.analysis.prob.toProblems
import b.language.server.config.Settings
import b.language.server.project.classicB.ClassicBProject
import de.prob.animator.command.CheckWellDefinednessCommand
import de.prob.animator.domainobjects.ErrorItem
import de.prob.statespace.StateSpace
import java.util.logging.Logger

/**
 * Checks a classical B machine with ProB: parsing, type checking, and, as the settings ask for,
 * extended static checks and well-definedness checks. Remembers the machine's identifiers.
 */
class ClassicBChecker(
    private val session: ProBSession,
    private val identifiers: LastCheck<IdentifierCatalog>,
) : Checker<ClassicBProject> {
    override fun check(
        project: ClassicBProject,
        settings: Settings,
    ): Either<AnalysisFailure, List<Problem>> =
        either {
            session.use(ProBHome.of(settings.probHome)).bind()
            session.unloadCurrent()
            val model = ClassicBModel(session, project)
            val (problems, warnings) = session.collectingWarnings { load(project, model, settings) }
            warnings.toProblems(project.mainFile) + problems
        }

    private fun load(
        project: ClassicBProject,
        model: ClassicBModel,
        settings: Settings,
    ): List<Problem> =
        session.withStateSpace(ProBPreferences.checking(settings)) { stateSpace ->
            val errors =
                when (val failure = model.loadInto(stateSpace).leftOrNull()) {
                    null -> emptyList()
                    is LoadFailure.Rejected -> failure.failure.errors
                    is LoadFailure.Unreadable -> return@withStateSpace failure.toProblems(project.mainFile)
                }
            if (errors.any { it.type == ErrorItem.Type.ERROR }) return@withStateSpace errors.toProblems(project.mainFile)

            ProBFailure
                .catching { IdentifierCatalog.of(stateSpace) }
                .onRight { identifiers[project.mainFile] = it }
                .onLeft { log.info("could not describe identifiers: ${it.message("")}") }
            val extended = if (settings.strictChecks) stateSpace.performExtendedStaticChecks() else emptyList()
            if (settings.wdChecks) checkWellDefinedness(stateSpace)
            (errors + extended).toProblems(project.mainFile)
        }

    /** ProB reports the well-definedness conditions it cannot discharge as warnings. */
    private fun checkWellDefinedness(stateSpace: StateSpace) {
        Either
            .catch { stateSpace.execute(CheckWellDefinednessCommand()) }
            .onLeft { log.warning("well-definedness check failed: ${it.message}") }
    }

    private companion object {
        val log: Logger = Logger.getLogger(ClassicBChecker::class.java.name)
    }
}
