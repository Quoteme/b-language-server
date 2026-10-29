package b.language.server.analysis.classicB

import b.language.server.analysis.Checker
import b.language.server.analysis.LastCheck
import b.language.server.analysis.Problem
import b.language.server.analysis.prob.ProBHome
import b.language.server.analysis.prob.ProBPreferences
import b.language.server.analysis.prob.ProBSession
import b.language.server.analysis.prob.toProblems
import b.language.server.config.Settings
import b.language.server.project.classicB.ClassicBProject
import de.prob.animator.command.CheckWellDefinednessCommand
import de.prob.animator.domainobjects.ErrorItem
import de.prob.exception.ProBError
import de.prob.statespace.StateSpace
import java.io.IOException
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
    ): List<Problem> {
        session.use(ProBHome.of(settings.probHome))
        session.unloadCurrent()
        val model = ClassicBModel(session, project)
        val (errors, warnings) = session.collectingWarnings { load(project, model, settings) }
        return warnings.toProblems(project.mainFile) + errors.toProblems(project.mainFile)
    }

    private fun load(
        project: ClassicBProject,
        model: ClassicBModel,
        settings: Settings,
    ): List<ErrorItem> {
        val stateSpace = session.createStateSpace(ProBPreferences.checking(settings))
        val errors = mutableListOf<ErrorItem>()
        try {
            model.loadInto(stateSpace)
        } catch (e: IOException) {
            log.info("could not read ${project.mainFile}: ${e.message}")
        } catch (e: ProBError) {
            errors += e.errors
        }
        if (errors.none { it.type == ErrorItem.Type.ERROR }) {
            try {
                identifiers[project.mainFile] = IdentifierCatalog.of(stateSpace)
            } catch (e: ProBError) {
                log.info("could not describe identifiers: ${e.message}")
            }
            if (settings.strictChecks) errors += stateSpace.performExtendedStaticChecks()
            if (settings.wdChecks) checkWellDefinedness(stateSpace)
        }
        stateSpace.kill()
        return errors
    }

    /** ProB reports the well-definedness conditions it cannot discharge as warnings. */
    private fun checkWellDefinedness(stateSpace: StateSpace) {
        try {
            stateSpace.execute(CheckWellDefinednessCommand())
        } catch (e: RuntimeException) {
            log.warning("well-definedness check failed: ${e.message}")
        }
    }

    private companion object {
        val log: Logger = Logger.getLogger(ClassicBChecker::class.java.name)
    }
}
