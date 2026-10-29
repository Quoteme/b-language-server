package b.language.server.analysis.prob

import de.prob.statespace.StateSpace

/** A model ready to be loaded into a ProB state space. */
fun interface LoadableModel {
    fun loadInto(stateSpace: StateSpace)
}
