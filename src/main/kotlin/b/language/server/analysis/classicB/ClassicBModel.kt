package b.language.server.analysis.classicB

import arrow.core.Either
import arrow.core.flatMap
import b.language.server.analysis.prob.LoadFailure
import b.language.server.analysis.prob.LoadableModel
import b.language.server.analysis.prob.ProBFailure
import b.language.server.analysis.prob.ProBSession
import b.language.server.project.classicB.ClassicBProject
import de.prob.scripting.FactoryProvider
import de.prob.statespace.StateSpace
import java.io.IOException

/** A classical B machine, which ProB reads from its file together with the files it includes. */
class ClassicBModel(
    private val session: ProBSession,
    private val project: ClassicBProject,
) : LoadableModel {
    /** ProB's reader for the file's extension. */
    private val factory = session.instance(FactoryProvider.factoryClassFromExtension(project.extension))

    override fun loadInto(stateSpace: StateSpace): Either<LoadFailure, Unit> =
        Either
            .catchOrThrow<IOException, Either<LoadFailure, Unit>> {
                ProBFailure
                    .catching { factory.extract(project.mainFile.absolutePath).loadIntoStateSpace(stateSpace) }
                    .map {}
                    .mapLeft(LoadFailure::Rejected)
            }.mapLeft { LoadFailure.Unreadable(project.mainFile, it.message) }
            .flatMap { it }
}
