package b.language.server.analysis.classicB

import b.language.server.analysis.Problem
import b.language.server.analysis.prob.ModelCheckOutcome
import b.language.server.analysis.prob.ProBModelChecker
import b.language.server.analysis.prob.ProBSession
import b.language.server.project.classicB.ClassicBProject

/** Model checks a classical B machine; what is found is shown at the start of the file. */
class ClassicBModelChecker(
    session: ProBSession,
) : ProBModelChecker<ClassicBProject, ClassicBModel>(session) {
    override fun prepare(project: ClassicBProject) = ClassicBModel(session, project)

    override fun locate(
        project: ClassicBProject,
        model: ClassicBModel,
        error: ModelCheckOutcome.ErrorFound,
    ): List<Problem> = listOf(atStart(project, error))
}
