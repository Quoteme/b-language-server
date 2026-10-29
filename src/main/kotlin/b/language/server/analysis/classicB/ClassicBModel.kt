package b.language.server.analysis.classicB

import b.language.server.analysis.prob.LoadableModel
import b.language.server.analysis.prob.ProBSession
import b.language.server.project.classicB.ClassicBProject
import de.prob.scripting.FactoryProvider
import de.prob.statespace.StateSpace

/** A classical B machine, which ProB reads from its file together with the files it includes. */
class ClassicBModel(
    private val session: ProBSession,
    private val project: ClassicBProject,
) : LoadableModel {
    /** Picks ProB's reader by the file's extension, failing early for unknown ones. */
    private val factory = session.instance(FactoryProvider.factoryClassFromExtension(project.extension))

    override fun loadInto(stateSpace: StateSpace) {
        factory.extract(project.mainFile.absolutePath).loadIntoStateSpace(stateSpace)
    }
}
