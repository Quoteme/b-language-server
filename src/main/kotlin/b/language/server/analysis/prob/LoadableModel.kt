package b.language.server.analysis.prob

import arrow.core.Either
import de.prob.statespace.StateSpace

/** A model ready to be loaded into a ProB state space. */
fun interface LoadableModel {
    fun loadInto(stateSpace: StateSpace): Either<LoadFailure, Unit>
}
